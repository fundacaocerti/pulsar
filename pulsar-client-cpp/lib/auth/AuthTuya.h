#include <pulsar/Authentication.h>
#include <string>

namespace pulsar {


class AuthDataTuya : public AuthenticationDataProvider {
   public:
    AuthDataTuya(const std::string& id, const std::string& key);
    ~AuthDataTuya();

    bool hasDataForTuya();
    std::string getTuyaAccessId();
    std::string getTuyaAccessKey();

   private:
    std::string accessId_;
    std::string accessKey_;
};

}  // namespace pulsar
