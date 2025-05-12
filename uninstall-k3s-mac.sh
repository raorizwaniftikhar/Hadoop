#!/bin/bash

set -e

VM_NAME="k3s-vm"

echo "🛑 Stopping and deleting Multipass VM: $VM_NAME ..."
multipass delete $VM_NAME
multipass purge

echo "🧹 Removing kubeconfig files ..."
rm -f ~/k3s-config.yaml
rm -rf ~/.kube/config

echo "🧼 Removing any leftover Multipass configs (optional) ..."
rm -rf ~/Library/Caches/multipass
rm -rf ~/Library/Logs/multipass
rm -rf ~/Library/Application\ Support/multipass

echo "🎯 Uninstalling Multipass (optional) ..."
brew uninstall --cask multipass || echo "Multipass not installed with Homebrew."

echo "✅ Cleanup complete."
