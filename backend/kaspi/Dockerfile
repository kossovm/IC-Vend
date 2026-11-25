# Сервис kaspi-pos-automation (https://github.com/tapter-dev/kaspi-pos-automation), закреплённый на проверенном коммите.
FROM node:20-alpine
RUN apk add --no-cache git tzdata
ARG KASPI_REF=9b0000d38716a7c4b36867a5642a4768a3a4552f
WORKDIR /app
RUN git clone https://github.com/tapter-dev/kaspi-pos-automation.git . \
 && git checkout "$KASPI_REF" \
 && npm ci --omit=dev \
 && rm -rf .git
COPY entrypoint.sh /entrypoint.sh
ENV TZ=Asia/Almaty PORT=3000
VOLUME /data
EXPOSE 3000
ENTRYPOINT ["/bin/sh", "/entrypoint.sh"]
