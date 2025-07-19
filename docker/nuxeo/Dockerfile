# Nuxeo server image
#
# It includes a bare Nuxeo server without any Nuxeo package installed and some basic Open Source converters.
#
# It uses a multi-stage build to copy and unzip the Nuxeo server ZIP file.

#ARG EL_VERSION=9
#ARG ORACLE_LINUX_TAG=$EL_VERSION-slim

# ------------------------------------------------------------------------
# Build stage
FROM azul/zulu-openjdk:17 AS builder

RUN apt-get update && apt-get install -y --no-install-recommends \
  procps \
  unzip \
  && rm -rf /var/lib/apt/lists/*

COPY target/distrib/nuxeo-server-tomcat-*.zip /tmp/nuxeo-distribution-tomcat.zip

ENV NUXEO_HOME=/distrib

RUN mkdir -p /tmp/nuxeo-distribution \
  && unzip -q -d /tmp/nuxeo-distribution /tmp/nuxeo-distribution-tomcat.zip \
  && DISTDIR=$(/bin/ls /tmp/nuxeo-distribution | head -n 1) \
  && mv /tmp/nuxeo-distribution/$DISTDIR $NUXEO_HOME \
  && sed -i -e "s/^org.nuxeo.distribution.package.*/org.nuxeo.distribution.package=docker/" $NUXEO_HOME/templates/common/config/distribution.properties \
  # Make sure the packages/* directories belong to nuxeo, to avoid a permission issue with nuxeoctl mp-install
  # in case they are mounted as a volume (then owned by root:root)
  && mkdir -p $NUXEO_HOME/packages/backup \
  && mkdir -p $NUXEO_HOME/packages/store \
  && mkdir -p $NUXEO_HOME/packages/tmp \
  && rm -rf /tmp/nuxeo-distribution* \
  && chmod +x $NUXEO_HOME/bin/*ctl $NUXEO_HOME/bin/*.sh \
  && chmod -R g+rwX $NUXEO_HOME

# ------------------------------------------------------------------------
# Target stage
FROM oraclelinux:9-slim

ARG BUILD_TAG
ARG SCM_REF
ARG VERSION

ARG EL_VERSION=9
ARG LIBREOFFICE_VERSION=7.5.8
ARG TARGETPLATFORM

LABEL org.nuxeo.build-tag=$BUILD_TAG
LABEL org.nuxeo.scm-ref=$SCM_REF
LABEL org.nuxeo.version=$VERSION
# Override parent ones
LABEL org.label-schema.build-date=""
LABEL org.label-schema.license="Apache 2.0"
LABEL org.label-schema.name="Nuxeo Server"
LABEL org.label-schema.vendor="Nuxeo"
LABEL org.opencontainers.image.created=""
LABEL org.opencontainers.image.licenses="Apache 2.0"
LABEL org.opencontainers.image.title="Nuxeo Server"
LABEL org.opencontainers.image.vendor="Nuxeo"

# Configure Zulu Repository
RUN rpm --import http://repos.azulsystems.com/RPM-GPG-KEY-azulsystems \
  && rpm --install https://cdn.azul.com/zulu/bin/zulu-repo-1.0.0-1.noarch.rpm

# Install required software
# Mount nuxeo-public as secret even if it is not secret in order to not leave the file in the image
RUN --mount=type=secret,id=nuxeo-public,target=/etc/yum.repos.d/nuxeo-public.repo \
  microdnf -y update \
  # Required by Remi's RPM repository, also, it provides dnf
  && microdnf -y install epel-release \
  && dnf -y install \
    # find is not included in the oraclelinux slim base image
    findutils \
    ghostscript \
    glibc-langpack-en \
    # Add CJK fonts
    google-noto-cjk-fonts-common \
    # Provided by yum-public from packages.nuxeo.com
    libwpd-tools \
    # Required by ExifTool to extract binary metadata from LibreOffice documents
    perl-Archive-Zip \
    perl-Image-ExifTool \
    poppler-utils \
    # ps is not included in the base oraclelinux image
    procps \
    unzip \
    wget \
    # Install Java before LibreOffice that depends on it
    zulu17-jdk \
  # Install LibreOffice, only for amd64 since not available for arm64
  && if [ "$TARGETPLATFORM" = "linux/amd64" ]; then \
       curl -f -L https://packages.nuxeo.com/repository/document-foundation-raw/LibreOffice_${LIBREOFFICE_VERSION}_Linux_x86-64_rpm.tar.gz | tar -C /tmp -xzv \
       && dnf -y install /tmp/LibreOffice_${LIBREOFFICE_VERSION}*/RPMS/*.rpm \
       && ln -s /opt/libreoffice$(echo $LIBREOFFICE_VERSION | cut -f 1,2 -d ".")/program/soffice /usr/bin/soffice \
       && ln -s /opt/libreoffice$(echo $LIBREOFFICE_VERSION | cut -f 1,2 -d ".")/program/soffice /usr/bin/libreoffice \
       && rm -rf /tmp/LibreOffice_${LIBREOFFICE_VERSION}* ; \
     fi \
  # ImageMagick 7 for Oracle Linux 9 is only provided by Remi's RPM repository
  && dnf -y install https://rpms.remirepo.net/enterprise/remi-release-${EL_VERSION}.rpm \
  && dnf -y --enablerepo=remi install ImageMagick7 \
  && dnf -y install gettext \
  && dnf clean all

# Remove perl-Net-SSLeay example private key
RUN rm /usr/share/doc/perl-Net-SSLeay/examples/server_key.pem

# Remove setuid/setgid binaries from images for security
RUN find / -ignore_readdir_race -perm 6000 -type f -exec chmod a-s {} \; || true

# Set permissions on writeable directories to support arbitrary user IDs for OpenShift.
# These directories must be owned by the root group and be readable/writable by that group.
# See https://docs.openshift.com/container-platform/3.5/creating_images/guidelines.html#use-uid
RUN chmod g=u /etc/passwd

# Set an UTF-8 LANG
ENV LANG=en_US.utf8

# Add a nuxeo user with a fixed UID
# We chose an arbitrary UID that doesn't conflict with possibly existing users
ENV NUXEO_USER=nuxeo
RUN useradd -m -d /home/$NUXEO_USER -u 900 -s /bin/bash $NUXEO_USER

ENV NUXEO_HOME=/opt/nuxeo/server
ENV NUXEO_CONF=/etc/nuxeo/nuxeo.conf
ENV PATH=$NUXEO_HOME/bin:$PATH

# Copy scripts and configuration files and create needed folders
# COPY --chmod only supports octal notation, using 775 that is equivalent to rwxrwxr-x
COPY --chown=900:0 --chmod=775 rootfs /

# Copy Nuxeo distribution
COPY --chown=900:0 --from=builder /distrib $NUXEO_HOME

VOLUME /var/lib/nuxeo
VOLUME /var/log/nuxeo
VOLUME /tmp

COPY nuxeo-web-ui-marketplace-3.1.16-SNAPSHOT.zip /tmp/nuxeo-web-ui-marketplace-3.1.16-SNAPSHOT.zip
COPY nuxeo-onesaitplatform-keycloak-package-2023.20-SNAPSHOT.zip /tmp/nuxeo-onesaitplatform-keycloak-package-2023.20-SNAPSHOT.zip
COPY nuxeo-onesaitplatform-segittur-keycloak-package-2023.20-SNAPSHOT.zip /tmp/nuxeo-onesaitplatform-segittur-keycloak-package-2023.20-SNAPSHOT.zip
COPY onlyoffice-nuxeo-package-2.1.0.zip /tmp/onlyoffice-nuxeo-package-2.1.0.zip
COPY docker-entrypoint.sh /
COPY --chown=900:0 nuxeo.conf /etc/nuxeo/nuxeo.conf.template
COPY mysql-connector-j-8.4.0.jar $NUXEO_HOME/lib/mysql-connector-j-8.4.0.jar
COPY mariadb-java-client-3.5.1.jar $NUXEO_HOME/lib/mariadb-java-client-3.5.1.jar
COPY postgresql-42.7.5.jar $NUXEO_HOME/lib/postgresql-42.7.5.jar
COPY log4j2.xml $NUXEO_HOME/lib/log4j2.xml


ENV NUXEO_URL=https://development.onesaitplatform.com/nuxeo \
    NUXEO_KEYCLOAK_REALM=onesaitplatform \
    NUXEO_KEYCLOAK_AUTHSERVERURL=https://development.onesaitplatform.com/auth \
    NUXEO_KEYCLOAK_RESOURCE=onesaitplatform \
    NUXEO_KEYCLOAK_PUBLICCLIENT=false \
    NUXEO_KEYCLOAK_CREDENTIALS_SECRET=onesaitplatform \
    NUXEO_KEYCLOAK_USERESOURCEROLEMAPPINGS=true \
    NUXEO_DB_NAME=onesaitplatform_nuxeo \
    NUXEO_DB_USER=root \
    NUXEO_DB_PASSWORD=changeIt! \
    NUXEO_DB_HOST=configdb \
    NUXEO_DB_PORT=3306 \
    NUXEO_DB_JDBC_URL=jdbc:mysql://configdb:3306/onesaitplatform_nuxeo?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=UTF-8&allowMultiQueries=true&useSSL=false&autoReconnect=true \
    NUXEO_DB_DRIVER=com.mysql.cj.jdbc.Driver \
    NUXEO_DB_MIN_POOL_SIZE=3 \
    NUXEO_DB_MAX_POOL_SIZE=30 \
    NUXEO_VCS_MIN_POOL_SIZE=3 \
    NUXEO_VCS_MAX_POOL_SIZE=20 \
    NUXEO_PACKAGES="/tmp/nuxeo-web-ui-marketplace-3.1.16-SNAPSHOT.zip /tmp/nuxeo-onesaitplatform-keycloak-package-2023.20-SNAPSHOT.zip" \
    INITIAL_JAVA_OPTS="-Xms256m -Xmx1g" \
    ONESAITPLATFORM_CONFIG_JDBC_URL=jdbc:mysql://configdb:3306/onesaitplatform_config \
    ONESAITPLATFORM_CONFIG_JDBC_USER=root \
    ONESAITPLATFORM_CONFIG_JDBC_PASSWORD=changeIt! \
    ONESAITPLATFORM_CONFIG_JDBC_DRIVER=com.mysql.cj.jdbc.Driver \
    ADMINISTRATORS_MAPPING_PLATFORM_ROLES=ROLE_ADMINISTRATOR,ROLE_PLATFORM_ADMIN,ADMINISTRADOR_PID,GESTOR_PID \
    MEMBERS_MAPPING_PLATFORM_ROLES=ROLE_DATASCIENTIST,ROLE_DATAVIEWER,ROLE_DEVELOPER,ROLE_PARTNER,ROLE_USER \
    SERVICE_NAME_MAPPING_PLATFORM_ROLES_ENV=GESTOR_SERVICIO \
    NUXEO_MAIL_TRANSPORT_HOST="" \
    NUXEO_MAIL_TRANSPORT_PORT="" \
    NUXEO_MAIL_TRANSPORT_AUTH="" \
    NUXEO_MAIL_TRANSPORT_USER="" \
    NUXEO_MAIL_FROM="" \
    NUXEO_MAIL_TRANSPORT_PASSWORD="" \
    NUXEO_MAIL_TRANSPORT_PROTOCOL="" \
    NUXEO_MAIL_TRANSPORT_STARTTLS_ENABLE="" \
    DESTINATION_NAME_MAPPING_PLATFORM_ROLES_ENV=ADMINISTRADOR_DESTINO,GESTOR_DESTINO,ADMINISTRADOR,GESTOR_SEGITTUR


EXPOSE 8080
ENTRYPOINT ["/docker-entrypoint.sh"]
CMD ["nuxeoctl", "console"]

# Run as a non root user with a fixed UID
USER 900
