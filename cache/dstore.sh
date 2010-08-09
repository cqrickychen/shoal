#! /bin/sh

usage() {
    echo "Usage:"
    echo "    shell <membername> <groupname>"
    exit 0
}

if [ $# -lt 2 ]; then
     echo "Error: membername and groupname must be specified"
     usage
fi
if [ "${1}" = "-h" ]; then
     usage
fi

CACHE_HOME=.

CP=${HA_API_JAR}:${CACHE_HOME}/target/classes:${CACHE_HOME}/../gms/api/target/shoal-gms-api.jar:${CACHE_HOME}/../gms/impl/target/shoal-gms-impl.jar:${CACHE_HOME}/../gms/lib/grizzly-framework.jar:${CACHE_HOME}/../gms/lib/grizzly-utils.jar


java -cp ${CP}  -Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005 org.shoal.ha.cache.impl.util.StoreableBackingStoreShell cache $1 $2
