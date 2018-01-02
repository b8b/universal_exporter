.include "gradle.properties"

#install layout
SYSCONFDIR=${PREFIX}/etc
RC_D_DIR=${SYSCONFDIR}/rc.d
SHAREDSTATEDIR=${PREFIX}/share/${APP}
LOGDIR=/var/log/${APP}
INSTALL_SYS=install -o root -g wheel
INSTALL_CONF=install -o root -g ${GROUP}
INSTALL_LOG=install -o ${USER} -g ${GROUP}

all: rc.d_${APP}

rc.d_${APP}: rc.d_${APP}.sh
	sed -e 's|@PREFIX@|${PREFIX}|g' -e 's|@VERSION@|${VERSION}|g' "$>" > "$@"

install: ${APP}.conf.sample rc.d_${APP} lib/${APP}-${VERSION}.jar
	${INSTALL_CONF} -m 640 ${APP}.conf.sample ${SYSCONFDIR}/
	${INSTALL_SYS} -m 555 rc.d_${APP} ${RC_D_DIR}/${APP}
	${INSTALL_SYS} -d -m 755 "${SHAREDSTATEDIR}"
	for f in lib/*; do ${INSTALL_SYS} -m 444 "$$f" "${SHAREDSTATEDIR}"/; done
	${INSTALL_LOG} -d -m 755 ${LOGDIR}/
