#!/bin/sh

set -eu

# Figuring out the IP of the docker host machine is a convoluted process. On Mac we pull that value from the special
# host.docker.internal hostname. Linux does not support that yet, so we have to use the routing table.
#
# See https://github.com/docker/for-linux/issues/264 to track host.docker.internal support on linux.
HOST_DOMAIN_IP="$(getent hosts host.docker.internal | awk '{ print $1 }')"

if [[ ! -z "${HOST_DOMAIN_IP}" ]]; then
    printf "${HOST_DOMAIN_IP}"
else
    printf "$(ip route | awk '/default/ { print $3 }')"
fi
