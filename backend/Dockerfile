# Multi-stage, so the shipped image carries a JRE and a jar and not Maven, the sources or
# the build cache. The runtime layer is about 80MB rather than about 800MB, which matters
# on a platform that pulls the image on every deploy.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# DEPENDENCIES BEFORE SOURCES, deliberately. Docker caches per layer, so copying the pom
# alone and resolving against it means a change to a .java file does not re-download the
# world. `go-offline` is imperfect — some plugins still reach out during `package` — but it
# turns a five-minute rebuild into about thirty seconds.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src

# TESTS ARE SKIPPED HERE, and that is a deliberate division rather than laziness: the suite
# needs ~90 seconds and a deploy should not be the first place it runs. Run `mvn verify` in
# CI or locally; a deploy that also tested would mean a red suite blocking a rollback.
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime

# TESSERACT, BECAUSE THE COLLATERAL READER SHELLS OUT TO IT.
#
# A customer pledging a plot or a vehicle uploads the title and the server reads the page
# by running `tesseract`. Without the binary every upload records ocr_status = FAILED.
#
# THAT FAILS QUIETLY, WHICH IS WHY THIS COMMENT IS LONG. A failed reading does not block an
# application by design -- the customer types the identifiers themselves and the reviewer is
# told the reader failed -- so a deployment missing this does not error. It just never
# pre-fills anything, never compares a declaration against the page, and shows the manager
# "the reader failed on this document" every single time, while working perfectly on every
# machine that happens to have tesseract installed.
#
# `tesseract-ocr-data-eng` IS A SEPARATE PACKAGE and is just as required: the binary with no
# training data exits non-zero on every page. Both are in Alpine's community repository. To
# read another language, add its data package here AND set OCR_LANGUAGES -- either alone
# does nothing.
#
# Before the USER line below, because apk needs root.
RUN apk add --no-cache tesseract-ocr tesseract-ocr-data-eng

# NOT ROOT. A JVM serving HTTP has no reason to be, and a container escape from a root
# process is a worse day than one from an unprivileged one.
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

WORKDIR /app
COPY --from=build /build/target/ibanking-api-0.1.0-SNAPSHOT.jar app.jar

EXPOSE 8080

# CONTAINER-AWARE HEAP. Without MaxRAMPercentage the JVM sizes its heap from the host's
# memory rather than the container's limit, then gets OOM-killed by the platform on a box
# far larger than the instance. 75% leaves room for metaspace, thread stacks and the pool.
#
# The port comes from the platform: Render injects PORT, and a service that ignores it is
# marked unhealthy with no explanation on the dashboard.
ENTRYPOINT ["sh", "-c", "java -XX:MaxRAMPercentage=75 -Dserver.port=${PORT:-8080} -jar app.jar"]
