# The release build of CI (.gitea/workflows/release.yaml) on the toolchain of ./Dockerfile. BuildKit does it all, so
# the job needs no bind mounts (act_runner's docker resolves those on the host, not in the job): the sources go in as
# the build context, the signing key as secrets that never land in a layer, Gradle's caches in a cache mount that
# outlives the build, and the result comes out with --output.
#
#   docker build -t sleepyowl-ci tools/ci
#   docker build --no-cache -f tools/ci/release.Dockerfile \
#     --secret id=keystore,src=release.jks --secret id=keystore_password,src=password.txt \
#     --secret id=key_alias,src=alias.txt --build-arg VERSION_NAME=0.1.1 -o out .
#
# out/ then holds what tools/ci/release.sh lists. --no-cache because secrets are not in the layer cache key: a cached
# layer would return an APK signed with whatever key built it; the Gradle cache mount survives it.
ARG CI_IMAGE=sleepyowl-ci

FROM ${CI_IMAGE} AS build
ARG VERSION_NAME=
WORKDIR /work
COPY . .
RUN --mount=type=cache,id=sleepyowl-gradle,target=/gradle,sharing=locked \
    --mount=type=secret,id=keystore \
    --mount=type=secret,id=keystore_password \
    --mount=type=secret,id=key_alias \
    sh tools/ci/release.sh /out

FROM scratch
COPY --from=build /out/ /
