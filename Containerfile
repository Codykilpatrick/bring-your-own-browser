FROM docker.io/rockylinux/rockylinux:9

# NVIDIA libs deliberately left out: nvidia-ctk CDI injects the host driver at runtime.
RUN dnf install -y epel-release && \
    dnf install -y chromium \
                   mesa-libEGL mesa-libGL libglvnd-egl libglvnd-glx \
                   dejavu-sans-fonts dejavu-serif-fonts liberation-fonts && \
    dnf clean all
