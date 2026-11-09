# Optional: a ready-made developer tools image, so environments start in seconds instead of
# installing packages on every start. Build and push it to a registry your cluster can pull from:
#
#   docker build -t registry.example.com/devtools:1.0 docker/devtools
#   docker push registry.example.com/devtools:1.0
#
# then start the app with LINUX_DEVTOOLS_IMAGE=registry.example.com/devtools LINUX_DEVTOOLS_TAG=1.0
FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      git build-essential cmake gdb openjdk-21-jdk-headless maven \
      python3 python3-pip python3-venv curl ca-certificates nano openssh-server \
 && rm -rf /var/lib/apt/lists/*
