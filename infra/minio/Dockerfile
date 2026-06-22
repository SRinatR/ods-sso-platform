FROM golang:1.24.8-alpine3.22 AS build

ARG MINIO_VERSION=RELEASE.2025-10-15T17-29-55Z
RUN apk add --no-cache bash git make perl
RUN git clone --branch "${MINIO_VERSION}" --depth 1 https://github.com/minio/minio.git /src/minio
WORKDIR /src/minio
RUN make build

FROM alpine:3.22
RUN apk add --no-cache ca-certificates curl tzdata \
    && addgroup -S minio \
    && adduser -S -G minio -h /data minio
COPY --from=build /src/minio/minio /usr/local/bin/minio
RUN chmod 0755 /usr/local/bin/minio \
    && mkdir -p /data \
    && chown -R minio:minio /data
USER minio
VOLUME ["/data"]
EXPOSE 9000 9001
ENTRYPOINT ["/usr/local/bin/minio"]
CMD ["server", "/data", "--console-address", ":9001"]
