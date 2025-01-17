/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.service.schema;

import static java.util.Objects.isNull;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.apache.pulsar.broker.service.schema.SchemaCompatibilityStrategy.BACKWARD_TRANSITIVE;
import static org.apache.pulsar.broker.service.schema.SchemaCompatibilityStrategy.FORWARD_TRANSITIVE;
import static org.apache.pulsar.broker.service.schema.SchemaCompatibilityStrategy.FULL_TRANSITIVE;
import static org.apache.pulsar.broker.service.schema.SchemaRegistryServiceImpl.Functions.toMap;
import static org.apache.pulsar.broker.service.schema.SchemaRegistryServiceImpl.Functions.toPairs;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.validation.constraints.NotNull;

import org.apache.bookkeeper.common.concurrent.FutureUtils;
import org.apache.pulsar.broker.service.schema.exceptions.IncompatibleSchemaException;
import org.apache.pulsar.broker.service.schema.proto.SchemaRegistryFormat;
import org.apache.pulsar.common.protocol.schema.SchemaData;
import org.apache.pulsar.common.protocol.schema.SchemaHash;
import org.apache.pulsar.common.schema.SchemaType;
import org.apache.pulsar.common.protocol.schema.SchemaVersion;

public class SchemaRegistryServiceImpl implements SchemaRegistryService {
    private final Map<SchemaType, SchemaCompatibilityCheck> compatibilityChecks;
    private final SchemaStorage schemaStorage;
    private final Clock clock;
    protected static final long NO_DELETED_VERSION = -1L;

    @VisibleForTesting
    SchemaRegistryServiceImpl(SchemaStorage schemaStorage, Map<SchemaType, SchemaCompatibilityCheck> compatibilityChecks, Clock clock) {
        this.schemaStorage = schemaStorage;
        this.compatibilityChecks = compatibilityChecks;
        this.clock = clock;
    }

    @VisibleForTesting
    SchemaRegistryServiceImpl(SchemaStorage schemaStorage, Map<SchemaType, SchemaCompatibilityCheck> compatibilityChecks) {
        this(schemaStorage, compatibilityChecks, Clock.systemUTC());
    }

    @Override
    @NotNull
    public CompletableFuture<SchemaAndMetadata> getSchema(String schemaId) {
        return getSchema(schemaId, SchemaVersion.Latest).thenApply((schema) -> {
                if (schema != null && schema.schema.isDeleted()) {
                    return null;
                } else {
                    return schema;
                }
            });
    }

    @Override
    @NotNull
    public CompletableFuture<SchemaAndMetadata> getSchema(String schemaId, SchemaVersion version) {
        return schemaStorage.get(schemaId, version).thenCompose(stored -> {
                if (isNull(stored)) {
                    return completedFuture(null);
                } else {
                    return Functions.bytesToSchemaInfo(stored.data)
                        .thenApply(Functions::schemaInfoToSchema)
                        .thenApply(schema -> new SchemaAndMetadata(schemaId, schema, stored.version));
                }
            }
        );
    }

    @Override
    public CompletableFuture<List<CompletableFuture<SchemaAndMetadata>>> getAllSchemas(String schemaId) {
        return schemaStorage.getAll(schemaId).thenApply(schemas ->
                schemas.stream().map(future -> future.thenCompose(stored ->
                    Functions.bytesToSchemaInfo(stored.data)
                        .thenApply(Functions::schemaInfoToSchema)
                        .thenApply(schema -> new SchemaAndMetadata(schemaId, schema, stored.version))
        )).collect(Collectors.toList()));
    }

    @Override
    @NotNull
    public CompletableFuture<SchemaVersion> putSchemaIfAbsent(String schemaId, SchemaData schema,
                                                              SchemaCompatibilityStrategy strategy) {
        return getSchema(schemaId, SchemaVersion.Latest)
            .thenCompose(
                (existingSchema) ->
                {
                    CompletableFuture<Long> maxDeleteVersionFuture;
                    if (existingSchema == null) {
                        maxDeleteVersionFuture = completedFuture(NO_DELETED_VERSION);
                    } else if (existingSchema.schema.isDeleted()) {
                        maxDeleteVersionFuture = completedFuture(((LongSchemaVersion)schemaStorage
                                .versionFromBytes(existingSchema.version.bytes())).getVersion());
                    } else {
                        if (isTransitiveStrategy(strategy)) {
                            maxDeleteVersionFuture = checkCompatibilityWithAll(schemaId, schema, strategy);

                        } else {
                            maxDeleteVersionFuture = new CompletableFuture<>();
                            trimDeletedSchemaAndGetList(schemaId).thenAccept(schemaAndMetadataList -> {
                                checkCompatibilityWithLatest(schemaId, schema, strategy).whenComplete((v, ex) -> {
                                    if (ex == null) {
                                        Long maxDeleteVersion = ((LongSchemaVersion)schemaStorage
                                                .versionFromBytes(schemaAndMetadataList.get(0).version.bytes())).getVersion() - 1L;
                                        maxDeleteVersionFuture.complete(maxDeleteVersion);
                                    } else {
                                        maxDeleteVersionFuture.completeExceptionally(ex);
                                    }
                                });
                            });
                        }
                    }
                    return maxDeleteVersionFuture;
                }
            ).thenCompose(maxDeleteVersion -> {
                byte[] context = SchemaHash.of(schema).asBytes();
                SchemaRegistryFormat.SchemaInfo info = SchemaRegistryFormat.SchemaInfo.newBuilder()
                        .setType(Functions.convertFromDomainType(schema.getType()))
                        .setSchema(ByteString.copyFrom(schema.getData()))
                        .setSchemaId(schemaId)
                        .setUser(schema.getUser())
                        .setDeleted(false)
                        .setTimestamp(clock.millis())
                        .addAllProps(toPairs(schema.getProps()))
                        .build();
                return schemaStorage.put(schemaId, info.toByteArray(), context, maxDeleteVersion);
            });
    }

    @Override
    @NotNull
    public CompletableFuture<SchemaVersion> deleteSchema(String schemaId, String user) {
        byte[] deletedEntry = deleted(schemaId, user).toByteArray();
        return trimDeletedSchemaAndGetList(schemaId).thenCompose(schemaAndMetadataList ->
                schemaStorage.put(schemaId, deletedEntry, new byte[]{}, ((LongSchemaVersion)schemaStorage
                .versionFromBytes(schemaAndMetadataList.get(0).version.bytes())).getVersion() - 1L));

    }

    @Override
    public CompletableFuture<Boolean> isCompatible(String schemaId, SchemaData schema, SchemaCompatibilityStrategy strategy) {
        return checkCompatible(schemaId, schema, strategy).thenApply(v -> true);
    }

    private static boolean isTransitiveStrategy(SchemaCompatibilityStrategy strategy) {
        if (FORWARD_TRANSITIVE.equals(strategy)
                || BACKWARD_TRANSITIVE.equals(strategy)
                || FULL_TRANSITIVE.equals(strategy)) {
            return true;
        }
        return false;
    }

    @Override
    public CompletableFuture<Void> checkCompatible(String schemaId, SchemaData schema,
                                                                    SchemaCompatibilityStrategy strategy) {
        switch (strategy) {
            case FORWARD_TRANSITIVE:
            case BACKWARD_TRANSITIVE:
            case FULL_TRANSITIVE:
                return checkCompatibilityWithAll(schemaId, schema, strategy).thenApply(maxDeleteVersion -> null);
            default:
                return checkCompatibilityWithLatest(schemaId, schema, strategy);
        }
    }

    @Override
    public SchemaVersion versionFromBytes(byte[] version) {
        return schemaStorage.versionFromBytes(version);
    }

    @Override
    public void close() throws Exception {
        schemaStorage.close();
    }

    private SchemaRegistryFormat.SchemaInfo deleted(String schemaId, String user) {
        return SchemaRegistryFormat.SchemaInfo.newBuilder()
            .setSchemaId(schemaId)
            .setType(SchemaRegistryFormat.SchemaInfo.SchemaType.NONE)
            .setSchema(ByteString.EMPTY)
            .setUser(user)
            .setDeleted(true)
            .setTimestamp(clock.millis())
            .build();
    }

    private void checkCompatible(SchemaAndMetadata existingSchema, SchemaData newSchema,
                                 SchemaCompatibilityStrategy strategy) throws IncompatibleSchemaException {
        SchemaHash existingHash = SchemaHash.of(existingSchema.schema);
        SchemaHash newHash = SchemaHash.of(newSchema);
        SchemaData existingSchemaData = existingSchema.schema;
        if (existingSchemaData.getType().isPrimitive()) {
            if (newSchema.getType() != existingSchemaData.getType()) {
                throw new IncompatibleSchemaException(String.format("Incompatible primitive schema: " +
                        "exists schema type %s, new schema type %s", existingSchemaData.getType(), newSchema.getType()));
            }
        } else {
            if (!newHash.equals(existingHash)) {
                compatibilityChecks.getOrDefault(newSchema.getType(), SchemaCompatibilityCheck.DEFAULT)
                        .checkCompatible(existingSchemaData, newSchema, strategy);
            }
        }
    }

    public CompletableFuture<Long> findSchemaVersion(String schemaId, SchemaData schemaData) {
        return trimDeletedSchemaAndGetList(schemaId).thenCompose(schemaAndMetadataList -> {
            SchemaHash newHash = SchemaHash.of(schemaData);
            for (SchemaAndMetadata schemaAndMetadata:schemaAndMetadataList) {
                if (newHash.equals(SchemaHash.of(schemaAndMetadata.schema))) {
                    return completedFuture(((LongSchemaVersion)schemaStorage
                            .versionFromBytes(schemaAndMetadata.version.bytes())).getVersion());
                }
            }
            return completedFuture(NO_SCHEMA_VERSION);
        });
    }

    private CompletableFuture<Void> checkCompatibilityWithLatest(String schemaId, SchemaData schema,
                                                                    SchemaCompatibilityStrategy strategy) {
        return getSchema(schemaId).thenCompose(existingSchema -> {
            if (existingSchema != null && !existingSchema.schema.isDeleted()) {
                CompletableFuture<Void> result = new CompletableFuture<>();
                try {
                    checkCompatible(existingSchema, schema, strategy);
                    result.complete(null);
                } catch (IncompatibleSchemaException e) {
                    result.completeExceptionally(e);
                }
                return result;
            } else {
                return FutureUtils.exception(new IncompatibleSchemaException("Do not have existing schema."));
            }
        });
    }

    private CompletableFuture<Long> checkCompatibilityWithAll(String schemaId, SchemaData schema,
                                                                     SchemaCompatibilityStrategy strategy) {

        return trimDeletedSchemaAndGetList(schemaId).thenCompose(schemaAndMetadataList -> {
            CompletableFuture<Long> result = new CompletableFuture<>();
            try {
                compatibilityChecks.getOrDefault(schema.getType(), SchemaCompatibilityCheck.DEFAULT).checkCompatible(schemaAndMetadataList
                        .stream()
                        .map(schemaAndMetadata -> schemaAndMetadata.schema)
                        .collect(Collectors.toList()), schema, strategy);
                result.complete(((LongSchemaVersion)schemaStorage.versionFromBytes(schemaAndMetadataList.get(0).version.bytes())).getVersion());
            } catch (IncompatibleSchemaException e) {
                result.completeExceptionally(e);
            }
            return result;
        });
    }

    public CompletableFuture<List<SchemaAndMetadata>> trimDeletedSchemaAndGetList(String schemaId) {
        return getAllSchemas(schemaId).thenCompose(FutureUtils::collect).thenApply(list -> {
            // Trim the prefix of schemas before the latest delete.
            int lastIndex = list.size() - 1;
            for (int i = lastIndex; i >= 0; i--) {
                if (list.get(i).schema.isDeleted()) {
                    if (i == lastIndex) { // if the latest schema is a delete, there's no schemas to compare
                        return Collections.<SchemaAndMetadata>emptyList();
                    } else {
                        return list.subList(i + 1, list.size());
                    }
                }
            }
            return list;
        });
    }

    interface Functions {
        static SchemaType convertToDomainType(SchemaRegistryFormat.SchemaInfo.SchemaType type) {
            if (type.getNumber() < 0) {
                return SchemaType.NONE;
            } else {
                // the value of type in `SchemaType` is always 1 less than the value of type `SchemaInfo.SchemaType`
                return SchemaType.valueOf(type.getNumber() - 1);
            }
        }

        static SchemaRegistryFormat.SchemaInfo.SchemaType convertFromDomainType(SchemaType type) {
            if (type.getValue() < 0) {
                return SchemaRegistryFormat.SchemaInfo.SchemaType.NONE;
            } else {
                return SchemaRegistryFormat.SchemaInfo.SchemaType.valueOf(type.getValue() + 1);
            }
        }

        static Map<String, String> toMap(List<SchemaRegistryFormat.SchemaInfo.KeyValuePair> pairs) {
            Map<String, String> map = new HashMap<>();
            for (SchemaRegistryFormat.SchemaInfo.KeyValuePair pair : pairs) {
                map.put(pair.getKey(), pair.getValue());
            }
            return map;
        }

        static List<SchemaRegistryFormat.SchemaInfo.KeyValuePair> toPairs(Map<String, String> map) {
            if (isNull(map)) {
                return Collections.emptyList();
            }
            List<SchemaRegistryFormat.SchemaInfo.KeyValuePair> pairs = new ArrayList<>(map.size());
            for (Map.Entry<String, String> entry : map.entrySet()) {
                SchemaRegistryFormat.SchemaInfo.KeyValuePair.Builder builder =
                    SchemaRegistryFormat.SchemaInfo.KeyValuePair.newBuilder();
                pairs.add(builder.setKey(entry.getKey()).setValue(entry.getValue()).build());
            }
            return pairs;
        }

        static SchemaData schemaInfoToSchema(SchemaRegistryFormat.SchemaInfo info) {
            return SchemaData.builder()
                .user(info.getUser())
                .type(convertToDomainType(info.getType()))
                .data(info.getSchema().toByteArray())
                .isDeleted(info.getDeleted())
                .props(toMap(info.getPropsList()))
                .build();
        }

        static CompletableFuture<SchemaRegistryFormat.SchemaInfo> bytesToSchemaInfo(byte[] bytes) {
            CompletableFuture<SchemaRegistryFormat.SchemaInfo> future;
            try {
                future = completedFuture(SchemaRegistryFormat.SchemaInfo.parseFrom(bytes));
            } catch (InvalidProtocolBufferException e) {
                future = new CompletableFuture<>();
                future.completeExceptionally(e);
            }
            return future;
        }
    }

}
