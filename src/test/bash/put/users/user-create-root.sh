# Updates the root user
source `dirname $0`/../../functions.sh PUT $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic root/root:$EXCHANGE_ROOTPW" -d '{
  "password": "'$EXCHANGE_PW'",
  "email": "'$EXCHANGE_EMAIL'"
}' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/users/$EXCHANGE_USER | $parse
