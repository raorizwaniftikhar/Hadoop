# 🌱 Start from the base Ubuntu image
FROM  ubuntu:latest

# Set environment variables to avoid interactive prompts
ENV DEBIAN_FRONTEND=noninteractive

# Install dependencies for Homebrew and required tools
RUN apt-get update && apt-get install -y git \
    && apt-get install -y openjdk-11-jdk \
    && apt-get install -y procps \
    && apt-get install -y python3 \
    && apt-get install -y maven \
    && apt-get install -y openssh-server \
    && apt-get install -y openssh-client \
    && apt-get install -y net-tools \
    && apt-get install -y curl \
    && apt-get install -y sudo \
    && apt-get install -y passwd \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# 👤 Set up SSH and the default users
RUN echo 'ubuntu:ubuntu' | chpasswd && \
    usermod -aG sudo ubuntu && \
    mkdir /var/run/sshd  && \
    echo 'root:root' | chpasswd && \
    sed -i 's/PermitRootLogin prohibit-password/PermitRootLogin yes/' /etc/ssh/sshd_config  && \
    sed -i 's/PermitRootLogin prohibit-password/PermitRootLogin yes/' /etc/ssh/sshd_config

# # Set JAVA_HOME for OpenJDK 11 installed
ENV JAVA_HOME="/usr/lib/jvm/java-11-openjdk-arm64"

# 🛠️ Define paths for Hadoop and its components
ENV HADOOP_HOME="/opt/hadoop"
ENV HADOOP_COMMON_HOME="$HADOOP_HOME"
ENV HADOOP_HDFS_HOME="$HADOOP_HOME"
ENV HADOOP_CONF_DIR="$HADOOP_HOME/etc/hadoop"
ENV HADOOP_MAPRED_HOME="$HADOOP_HOME"
ENV HADOOP_YARN_HOME="$HADOOP_HOME"


# 📦 Define paths for other big data components
ENV HBASE_HOME="/opt/hbase"
ENV FLUME_HOME="/opt/flume"
ENV HIVE_HOME="/opt/hive"

# ⚙️ Hadoop Java Options & IPv4 preference
ENV HADOOP_OPTS="-Djava.library.path=$HADOOP_HOME/lib/native"
ENV HADOOP_COMMON_LIB_NATIVE_DIR="$HADOOP_HOME/lib/native"
ENV HADOOP_OPTS="$HADOOP_OPTS -Djava.net.preferIPv4Stack=true"
ENV HADOOP_OPTS="$HADOOP_OPTS --add-opens java.base/java.lang=ALL-UNNAMED"

# 👤 Set default Hadoop users for its daemons
ENV HDFS_NAMENODE_USER=ubuntu
ENV HDFS_DATANODE_USER=ubuntu
ENV HDFS_SECONDARYNAMENODE_USER=ubuntu
ENV YARN_RESOURCEMANAGER_USER=ubuntu
ENV YARN_NODEMANAGER_USER=ubuntu
ENV HADOOP_USER_NAME=ubuntu

# 📂 Define Hadoop log and PID directories
ENV HADOOP_PID_DIR=/tmp/hadoop-root/pids
ENV HADOOP_LOG_DIR=/tmp/hadoop-root/logs

# 🧯 Disable unnecessary warnings
ENV HADOOP_HOME_WARN_SUPPRESS=true

# 📋 Hadoop logging configuration
ENV HADOOP_ROOT_LOGGER=INFO,console
ENV HADOOP_SECURITY_LOGGER=INFO,NullAppender
ENV HADOOP_NAMENODE_OPTS="-Dhadoop.security.logger=INFO,RFAS"
ENV HADOOP_DATANODE_OPTS="-Dhadoop.security.logger=ERROR,RFAS"
ENV HADOOP_SECONDARYNAMENODE_OPTS="-Dhadoop.security.logger=INFO,RFAS"
ENV HADOOP_JOBTRACKER_OPTS="-Dhadoop.security.logger=ERROR,JSA"
ENV HADOOP_TASKTRACKER_OPTS="-Dhadoop.security.logger=ERROR,JSA"
ENV HADOOP_CLIENT_OPTS="-Dhadoop.security.logger=ERROR,console"

# 🔒 Secure Hadoop Datanodes
ENV HADOOP_SECURE_DN_USER=hdfs
ENV HADOOP_SECURE_DN_PID_DIR=/tmp/hadoop-root/pids
ENV HADOOP_SECURE_DN_LOG_DIR=/tmp/hadoop-root/logs

# Secuerty Setting

# ENV HADOOP_OPTS="$HADOOP_OPTS -Djava.security.krb5.realm= -Djava.security.krb5.kdc="
# ENV HADOOP_OPTS="$HADOOP_OPTS -Djava.security.krb5.conf=/etc/krb5.conf"
# ENV HADOOP_OPTS="$HADOOP_OPTS -Djava.security.krb5.debug=true"
# ENV HADOOP_OPTS="$HADOOP_OPTS -Dsun.security.krb5.debug=true"

# # Add Hadoop ecosystem tools to PATH
ENV PATH=/bin:$JAVA_HOME/bin:$HADOOP_HOME/bin:$HIVE_HOME/bin:$HBASE_HOME/bin:$FLUME_HOME/bin

# Create necessary directories for Hadoop, Hive, HBase, etc.
RUN mkdir -p /tmp/hadoop-root/dfs/name \
    && mkdir -p /tmp/hadoop-root/dfs/data \
    && mkdir -p /usr/local/hive/warehouse \
    && mkdir -p /usr/local/hbase/data \
    && chown -R ubuntu:ubuntu /tmp/hadoop-root /usr/local/hive /usr/local/hbase \
    && chmod -R 777 /tmp/hadoop-root /usr/local/hive /usr/local/hbase
    
# Create the NameNode data directory and give permissions to ubuntu user
RUN mkdir -p /usr/local/hadoop/data/hdfs/namenode && \
    mkdir -p /usr/local/hadoop/data/hdfs/datanode && \
    chown -R ubuntu:ubuntu /usr/local/hadoop && \
    chmod -R 755 /usr/local/hadoop
   
# # Copy configuration files (ensure they exist in the build context)
COPY opt/hadoop /opt/hadoop
COPY opt/hbase /opt/hbase
COPY opt/hive /opt/hive
COPY opt/flume /opt/flume

# # Set permissions for Hadoop directories
# Ensure all files have the correct ownership and permissions
RUN chown -R ubuntu:ubuntu /opt/hadoop /opt/hbase /opt/hive /opt/flume \
    && chmod -R 777 /opt/hadoop /opt/hbase /opt/hive /opt/flume

# # Copy configuration files (ensure they exist in the build context)
COPY config/hadoop/* $HADOOP_HOME/etc/hadoop/
COPY config/hive/* $HIVE_HOME/conf/
COPY config/hbase/* $HBASE_HOME/conf/


# # Start services (ensure the script exists and is executable)
COPY start-services.sh /usr/local/bin/
USER root
RUN echo 'ubuntu ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/ubuntu && \
    chmod 0440 /etc/sudoers.d/ubuntu && \
    chmod +x /usr/local/bin/start-services.sh \
    && /opt/hive/bin/schematool -initSchema -dbType derby

# Set the default user to 'ubuntu' (as your user is 'ubuntu')
USER ubuntu

# # Generate SSH key at runtime
ENTRYPOINT ["/bin/bash", "-c", "ssh-keygen -t rsa -P '' -f ~/.ssh/id_rsa && cat ~/.ssh/id_rsa.pub >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && /usr/local/bin/start-services.sh"]

# # Command to start services
CMD ["/usr/local/bin/start-services.sh"]
