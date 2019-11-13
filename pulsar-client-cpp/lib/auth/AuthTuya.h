#include <pulsar/Authentication.h>
#include <string>

namespace pulsar {


class AuthDataTuya : public AuthenticationDataProvider {
   public:
    AuthDataTuya(const std::string& id, const std::string& key);
    ~AuthDataTuya();
    void AuthenticationDataProvider(const std::string& id, const std::string& key);
    bool hasDataFromCommand();
    bool hasDataForTuya();
    bool hasDataForHttp();
    std::string getTuyaAccessId();
    std::string getTuyaAccessKey();
    std::string getCommandData();

   private:
    std::string accessId_;
    std::string accessKey_;
    std::string commandData;
};

}  // namespace pulsar
