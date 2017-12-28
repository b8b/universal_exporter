APP=universal_exporter
PREFIX=/usr/local
VERSION=1.0-SNAPSHOT
USER=prometheus
GROUP=prometheus

#build layout
BUILD=build
LIBS=${BUILD}/libs
DISTTAR=${BUILD}/distributions/${APP}-${VERSION}.tar
DISTSUB=--strip-components=2 ${APP}-${VERSION}/lib

#install layout
SYSCONFDIR=${PREFIX}/etc
RC_D_DIR=${SYSCONFDIR}/rc.d
SHAREDSTATEDIR=${PREFIX}/share/${APP}
LOGDIR=/var/log/${APP}
INSTALL_SYS=install -o root -g wheel
INSTALL_CONF=install -o root -g ${GROUP}
INSTALL_LOG=install -o ${USER} -g ${GROUP}

all: ${BUILD}/rc.d/${APP}

${BUILD}/rc.d/${APP}: rc.d_${APP}.sh
	mkdir -p ${BUILD}/rc.d
	sed -e 's|@PREFIX@|${PREFIX}|g' -e 's|@VERSION@|${VERSION}|g' "$>" > "$@"

install: ${APP}.conf ${BUILD}/rc.d/${APP} ${DISTTAR}
	${INSTALL_CONF} -m 640 ${APP}.conf ${SYSCONFDIR}/
	${INSTALL_SYS} -m 555 ${BUILD}/rc.d/${APP} ${RC_D_DIR}/${APP}
	${INSTALL_SYS} -d -m 755 "${SHAREDSTATEDIR}"
	tar xf ${DISTTAR} -C ${SHAREDSTATEDIR}/ ${DISTSUB}
	${INSTALL_SYS} -m 444 ${LIBS}/${APP}-${VERSION}.jar ${SHAREDSTATEDIR}/
	${INSTALL_LOG} -d -m 755 ${LOGDIR}/
