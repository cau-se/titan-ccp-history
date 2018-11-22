FROM openjdk:10-slim

ADD build/distributions/titanccp-aggregation.tar /

EXPOSE 80

CMD export JAVA_OPTS=-Dorg.slf4j.simpleLogger.defaultLogLevel=$LOG_LEVEL \
    && /titanccp-aggregation/bin/titanccp-aggregation