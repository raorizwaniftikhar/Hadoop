#!/bin/bash

set -e

VM_NAME="k3s-vm"
MEMORY="4G"
DISK="20G"
brew install --cask multipass

echo "🚀 Launching Multipass VM: $VM_NAME ..."
multipass launch --name $VM_NAME --memory $MEMORY --disk $DISK

echo "📦 Installing K3s inside VM ..."
multipass exec $VM_NAME -- bash -c "curl -sfL https://get.k3s.io | sh -"

echo "📂 Fetching Kubernetes config ..."
multipass exec $VM_NAME -- sudo cat /etc/rancher/k3s/k3s.yaml > ~/k3s-config.yaml

VM_IP=$(multipass info $VM_NAME | grep IPv4 | awk '{print $2}')

echo "🔧 Updating kubeconfig with VM IP ($VM_IP) ..."
sed -i '' "s/127.0.0.1/$VM_IP/g" ~/k3s-config.yaml

mkdir -p ~/.kube
cp ~/k3s-config.yaml ~/.kube/config

echo "✅ Testing Kubernetes cluster ..."
kubectl get nodes

echo "🎉 Done! You now have Kubernetes running in Multipass on your Mac."
