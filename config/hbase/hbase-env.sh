# HBase Environment Settings

# Set Java Home
export JAVA_HOME="/usr/lib/jvm/java-11-openjdk-arm64"

# Set HBase Options
export HBASE_OPTS="-XX:+UseG1GC -Xmx2g"

# Set HBase Log Directory
export HBASE_LOG_DIR=/usr/local/hbase/logs

# Set HBase to use Zookeeper in secure mode
export HBASE_MANAGES_ZK=true
