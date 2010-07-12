#!/bin/sh +x

#
# Copyright 2004-2005 Sun Microsystems, Inc.  All rights reserved.
# Use is subject to license terms.
#
 #
 # The contents of this file are subject to the terms
 # of the Common Development and Distribution License
 # (the License).  You may not use this file except in
 # compliance with the License.
 #
 # You can obtain a copy of the license at
 # https://shoal.dev.java.net/public/CDDLv1.0.html
 #
 # See the License for the specific language governing
 # permissions and limitations under the License.
 #
 # When distributing Covered Code, include this CDDL
 # Header Notice in each file and include the License file
 # at
 # If applicable, add the following below the CDDL Header,
 # with the fields enclosed by brackets [] replaced by
 # you own identifying information:
 # "Portions Copyrighted [year] [name of copyright owner]"
 #
 # Copyright 2006 Sun Microsystems, Inc. All rights reserved.
 #

PUBLISH_HOME=./dist
LIB_HOME=./lib

usage () {
    cat << USAGE 
Usage: $0 <parameters...> 
The required parameters are :
 <instance_id_token> <groupname> <membertype{CORE|SPECTATOR}> <Life In Milliseconds> <log level> <transport>{grizzly,jxta} <-l logdir> <-ts tcpstartport> <-tp tcpendport> <-ma multicastaddress> <-mp multicastport>

Life in milliseconds should be either 0 or at least 60000 to demo failure fencing.

<-l fullpathtologdir> <-ts tcpstartport>, <-te tcpendport>, <-ma multicastaddress>, <-mp multicastport> are optional parameters.
Grizzly and jxta transports have different defaults.
USAGE
   exit 0
}

MAINCLASS=com.sun.enterprise.ee.cms.tests.ApplicationServer

GRIZZLY_JARS=${PUBLISH_HOME}/shoal-gms-tests.jar:${PUBLISH_HOME}/shoal-gms.jar:${LIB_HOME}/grizzly-framework.jar:${LIB_HOME}/grizzly-utils.jar
JXTA_JARS=${PUBLISH_HOME}/shoal-gms-tests.jar:${PUBLISH_HOME}/shoal-gms.jar:${LIB_HOME}/grizzly-framework.jar:${LIB_HOME}/grizzly-utils.jar:${LIB_HOME}/jxta.jar
DEBUGARGS="-Xdebug -Xnoagent -Djava.compiler=NONE -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005 -DjxtaMulticastPoolsize=25"
NONDEBUGARGS="-Dcom.sun.management.jmxremote"

DEBUG=false
TCPSTARTPORT=""
TCPENDPORT=""
MULTICASTADDRESS="-DMULTICASTADDRESS=229.9.1.2"
MULTICASTPORT="-DMULTICASTPORT=2299"
#BIND_INTERFACE_ADDRESS="-DBIND_INTERFACE_ADDRESS=ipv4oripv6address"
TRANSPORT=grizzly

TEST_LOG_LEVEL=WARNING
LOG_LEVEL=INFO


JARS=${GRIZZLY_JARS}
DONEREQUIRED=false
while [ $# -ne 0 ]
do
     case ${1} in
       -h)
       usage
       exit 1
       ;;
       -debug)
       shift
       DEBUG=true
       ;;
       -l)
       shift
       LOGS_DIR="${1}"
       shift
       ;;
       -tl)
       shift
       TEST_LOG_LEVEL="${1}"
       shift
       ;;
       -ts)
       shift
       TCPSTARTPORT="-DTCPSTARTPORT=${1}"
       shift
       ;;
       -te)
       shift
       TCPENDPORT="-DTCPENDPORT=${1}"
       shift
       ;;
       -ma)
       shift
       MULTICASTADDRESS="-DMULTICASTADDRESS=${1}"
       shift
       ;;
       -mp)
       shift
       MULTICASTPORT="-DMULTICASTPORT=${1}"
       shift
       ;;
       *)
       if [ ${DONEREQUIRED} = false ]; then
           INSTANCEID=$1
           shift
           CLUSTERNAME=$1
           shift
           MEMBERTYPE=$1
           shift
           LIFEINMILLIS=$1
           shift
           LOGLEVEL=$1
           shift
           TRANSPORT=$1
           shift
           DONEREQUIRED=true
       else
          echo "ERRROR: ignoring invalid argument $1"
          shift
       fi
       ;;
     esac
done

if [ -z "${INSTANCEID}" -o -z "${CLUSTERNAME}" -o -z "${MEMBERTYPE}" -o -z "${LIFEINMILLIS}" -o -z "${LOGLEVEL}" -o -z "${TRANSPORT}" ]; then
    echo "ERROR: Missing a required argument"
    usage;
fi

if [ "${MEMBERTYPE}" != "CORE" -a "${MEMBERTYPE}" != "SPECTATOR" -a "${MEMBERTYPE}" != "WATCHDOG" ]; then
    echo "ERROR: Invalid membertype specified"
    usage;
fi
if [ "${TRANSPORT}" != "grizzly" -a "${TRANSPORT}" != "jxta" ]; then
    echo "ERROR: Invalid transport specified"
    usage;
fi

if [ $TRANSPORT != "grizzly" ]; then
    JARS=${JXTA_JARS}
fi

if [ ${DEBUG} = false ]; then
    OTHERARGS=${NONDEBUGARGS}
else
    OTHERARGS=${DEBUGARGS}
fi

#  If you run shoal over grizzly on JDK7, NIO.2 multicast channel used. Otherwise, blocking multicast server used
CMD="java ${OTHERARGS} -DMEMBERTYPE=${MEMBERTYPE} -DINSTANCEID=${INSTANCEID} -DCLUSTERNAME=${CLUSTERNAME} -DMESSAGING_MODE=true -DLIFEINMILLIS=${LIFEINMILLIS} -DLOG_LEVEL=${LOGLEVEL} -DTEST_LOG_LEVEL=${TEST_LOG_LEVEL} -cp ${JARS} ${TCPSTARTPORT} ${TCPENDPORT} -DSHOAL_GROUP_COMMUNICATION_PROVIDER=${TRANSPORT} ${MULTICASTADDRESS} ${MULTICASTPORT} ${BIND_INTERFACE_ADDRESS} ${MAINCLASS}"
if [ -z "${LOGS_DIR}" ]; then
   echo "Running using Shoal with transport ${TRANSPORT}"
   echo "=========================="
   echo ${CMD}
   echo "=========================="
   ${CMD} &
else
   if [ ! -d ${LOGS_DIR} ];then
      mkdir -p ${LOGS_DIR}
   fi
   #echo "LOGS_DIR=${LOGS_DIR}"
   echo "Running using Shoal with transport ${TRANSPORT}" >> ${LOGS_DIR}/${INSTANCEID}.log
   echo "==========================" >> ${LOGS_DIR}/${INSTANCEID}.log
   echo ${CMD} >> ${LOGS_DIR}/${INSTANCEID}.log
   echo "==========================" >> ${LOGS_DIR}/${INSTANCEID}.log
   ${CMD}  >> ${LOGS_DIR}/${INSTANCEID}.log 2>&1 &
fi

