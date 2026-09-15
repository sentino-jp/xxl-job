# base image
FROM eclipse-temurin:17-jre

# set params
ENV PARAMS=""

# set timezone
ENV TZ=Asia/Tokyo
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# copy jar
ADD target/xxl-job-executor-http-*.jar /app.jar

# executor embedded port
EXPOSE 9999

# command
# log home: -e LOG_HOME=/data/applogs
# jvm options: -e JAVA_OPTS="-Xms128m -Xmx256m"
# app params: -e PARAMS="--xxl.job.admin.addresses=http://xxl-job-admin:9280"
ENTRYPOINT ["sh","-c","java ${LOG_HOME:+-DLOG_HOME=$LOG_HOME} -jar $JAVA_OPTS /app.jar $PARAMS"]
