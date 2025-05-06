#!/bin/bash
set -euo pipefail

# ----------------------
# Error handler
# ----------------------
on_error() {
  local exit_code=$?
  local line_number=$1
  echo "$(date +'%Y-%m-%d %H:%M:%S') - ❌ Script failed at line $line_number with exit code $exit_code" | tee -a "$LOG_FILE"
  exit $exit_code
}
trap 'on_error $LINENO' ERR

# ----------------------
# Constants and Setup
# ----------------------
LOG_DIR="/home/ubuntu/logs"
LOG_FILE="$LOG_DIR/hadoop.log"
HOSTNAME=$(hostname)
ROLE="${HADOOP_ROLE:-$HOSTNAME}"

mkdir -p "$LOG_DIR"
touch "$LOG_FILE"
chmod 777 "$LOG_DIR" "$LOG_FILE"

log() {
  echo "$(date +'%Y-%m-%d %H:%M:%S') - $1" | tee -a "$LOG_FILE"
}

log "📦 Starting container with hostname: $HOSTNAME, role: $ROLE"

# ----------------------
# Environment Variables
# ----------------------
export JAVA_HOME="/usr/lib/jvm/java-11-openjdk-arm64"
export HADOOP_HOME="/opt/hadoop"
export HBASE_HOME="/opt/hbase"
export FLUME_HOME="/opt/flume"
export HIVE_HOME="/opt/hive"
export PATH="/bin:$JAVA_HOME/bin:$HADOOP_HOME/bin:$HIVE_HOME/bin:$HBASE_HOME/bin:$FLUME_HOME/bin:$PATH"

unset HDFS_DATANODE_SECURE_USER
unset HADOOP_SECURE_DN_USER

# ----------------------
# SSH Setup
# ----------------------
generate_ssh_keys() {
  log "🔧 Checking SSH key generation..."
  if [ ! -f ~/.ssh/id_rsa ]; then
    log "🔑 Generating SSH key..."
    mkdir -p ~/.ssh
    ssh-keygen -t rsa -P "" -f ~/.ssh/id_rsa
  fi
  log "🔑 SSH keys are available."
}

setup_ssh() {
  log "🔧 Setting up SSH directory and authorized keys..."
  mkdir -p ~/.ssh
  cat ~/.ssh/id_rsa.pub >> ~/.ssh/authorized_keys
  chmod 600 ~/.ssh/authorized_keys
  chmod 700 ~/.ssh
  log "🔑 SSH setup completed."
}

generate_ssh_host_keys() {
  log "🔧 Ensuring SSH host keys exist..."
  if [ ! -f /etc/ssh/ssh_host_rsa_key ]; then
    log "🔑 Generating SSH host keys..."
    ssh-keygen -A
  fi
  log "🔑 SSH host keys are available."
}

start_ssh_service() {
  log "🔐 Starting SSH service..."
  sudo chown ubuntu:ubuntu /etc/ssh/ssh_host_* || true
  sudo chmod 600 /etc/ssh/ssh_host_*_key || true
  sudo chmod 644 /etc/ssh/ssh_host_*_key.pub || true
  sudo /usr/sbin/sshd
  log "✅ SSH service started."
}

# ----------------------
# Hadoop /Mapreduce / HDFS / YARN
# ----------------------
format_namenode() {
  local dir="/usr/local/hadoop/data/hdfs/namenode"
  log "🔍 Checking if NameNode needs formatting..."
  if [ ! -d "$dir/current" ]; then
    log "🧹 Formatting NameNode..."
    $HADOOP_HOME/bin/hdfs namenode -format -force >> "$LOG_FILE" 2>&1
    hdfs dfs -mkdir -p /spark-history
    hdfs dfs -chmod -R 777 /spark-history
    hdfs dfs -mkdir -p /tmp/hadoop-yarn/staging
    hdfs dfs -chmod -R 777 /tmp
    hdfs dfs -mkdir -p /var/log/hadoop-yarn
    hdfs dfs -chmod -R 777 /var/log/hadoop-yarn
  else
    log "🗃️ NameNode already formatted."
  fi
}

start_mapreduce_jobhistory() {
  log "🛰️ Starting MapReduce JobHistory Server..."
  $HADOOP_HOME/bin/mapred --daemon start historyserver >> "$LOG_FILE" 2>&1
}

start_hdfs() {
  log "🚀 Starting HDFS..."
  $HADOOP_HOME/sbin/start-dfs.sh >> "$LOG_FILE" 2>&1
}

start_datanode() {
  log "🚀 Starting HDFS DataNode..."
  $HADOOP_HOME/bin/hdfs --daemon start datanode >> "$LOG_FILE" 2>&1
}

start_yarn_resourcemanager() {
  log "🚀 Starting YARN ResourceManager..."
  $HADOOP_HOME/sbin/start-yarn.sh >> "$LOG_FILE" 2>&1
}

start_yarn_nodemanager() {
  log "🚀 Starting YARN NodeManager..."
  $HADOOP_HOME/bin/yarn --daemon start nodemanager >> "$LOG_FILE" 2>&1
}

format_zkfc() {
  log "🔧 Checking if ZooKeeper Failover Controller (ZKFC) needs formatting..."
  
  local zkfc_marker_file="/home/ubuntu/.zkfc_formatted"
  
  if [ ! -f "$zkfc_marker_file" ]; then
    log "🧹 Formatting ZKFC with ZooKeeper..."
    $HADOOP_HOME/bin/hdfs zkfc -formatZK -force >> "$LOG_FILE" 2>&1
    touch "$zkfc_marker_file"
    log "✅ ZKFC formatting completed and marker file created."
  else
    log "📌 ZKFC already formatted. Skipping."
  fi
}


# ----------------------
# Hive / HBase Services
# ----------------------
initialize_hive_metastore() {
  local metastore_db="$HIVE_HOME/metastore_db"
  log "🔍 Checking Hive metastore initialization..."
  if [ ! -f "$metastore_db/dbex.lck" ]; then
    log "📦 Initializing Hive metastore..."
    $HIVE_HOME/bin/schematool -initSchema -dbType derby >> "$LOG_FILE" 2>&1 || log "⚠️ Hive schema might already exist."
  else
    log "✅ Hive metastore already initialized."
  fi
}

start_hive_metastore() {
  log "🛰️ Starting Hive Metastore..."
  nohup $HIVE_HOME/bin/hive --service metastore >> "$LOG_FILE" 2>&1 &
}

start_hive_server2() {
  log "🛰️ Starting HiveServer2..."
  nohup $HIVE_HOME/bin/hive --service hiveserver2 >> "$LOG_FILE" 2>&1 &
}

start_hbase_master() {
  log "🛰️ Starting HBase Master..."
  $HBASE_HOME/bin/hbase-daemon.sh start master >> "$LOG_FILE" 2>&1
}

start_hbase_regionserver() {
  log "🛰️ Starting HBase RegionServer..."
  $HBASE_HOME/bin/hbase-daemon.sh start regionserver >> "$LOG_FILE" 2>&1
}

# ----------------------
# Known Hosts Setup
# ----------------------
add_ssh_known_hosts() {
  log "📥 Scanning SSH keys for cluster nodes..."
  for host in namenode datanode1 datanode2 resourcemanager; do
    ssh-keyscan -H "$host" >> ~/.ssh/known_hosts 2>/dev/null || true
  done
}

# ----------------------
# Main Execution
# ----------------------
generate_ssh_keys
setup_ssh
generate_ssh_host_keys
start_ssh_service
add_ssh_known_hosts


ROLE=$(hostname)

# Normalize roles from hostname patterns
if [[ "$ROLE" =~ ^namenode(-deployment)?-[0-9]+$ ]]; then
  ROLE="namenode"
elif [[ "$ROLE" =~ ^datanode-[0-9]+$ ]]; then
  ROLE="datanode"
elif [[ "$ROLE" =~ ^resourcemanager(-deployment)?-[0-9]+$ ]]; then
  ROLE="resourcemanager"
elif [[ "$ROLE" =~ ^nodemanager(-deployment)?-[0-9]+$ ]]; then
  ROLE="nodemanager"
elif [[ "$ROLE" =~ ^mapreduce-history(-deployment)?-[0-9]+$ ]]; then
  ROLE="mapreduce-history"
elif [[ "$ROLE" =~ ^zkfc-format(-job)?-[a-z0-9]+$ ]]; then
  ROLE="zkfc-format"
fi


case "$ROLE" in
  namenode)
    format_namenode
    start_hdfs
    start_hbase_master
    ;;
  datanode1|datanode2)
    start_datanode
    ;;
  resourcemanager)
    start_yarn_resourcemanager
    ;;
  nodemanager)
    start_yarn_nodemanager
    ;;
  hive-metastore)
    initialize_hive_metastore
    start_hive_metastore
    ;;
  hive-server2)
    start_hive_server2
    ;;
  hbase-master)
    start_hbase_master
    ;;
  hbase-regionserver)
    start_hbase_regionserver
    ;;
  mapreduce-history)
    start_mapreduce_jobhistory
    ;;
  zkfc-format)
    format_zkfc
    ;;
  *)
    log "⚠️ No matching service startup for role: $ROLE"
    ;;
esac


log "✅ [$ROLE] Services started successfully."

# Keep container running
tail -f /dev/null
