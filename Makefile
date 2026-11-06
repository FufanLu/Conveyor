# Thin convenience wrapper. Everything here is a plain Gradle or docker compose command.
COMPOSE := docker compose -f deploy/compose/compose.yaml
PROFILES ?=
PROFILE_FLAGS := $(foreach p,$(PROFILES),--profile $(p))

.PHONY: build test fmt infra-up infra-down infra-ps infra-stats run-%

build:            ## compile, format check, Error Prone, tests, coverage
	./gradlew build

test:
	./gradlew test

fmt:              ## apply palantir-java-format
	./gradlew spotlessApply

infra-up:         ## make infra-up PROFILES="search obs"
	$(COMPOSE) $(PROFILE_FLAGS) up -d --wait

infra-down:
	$(COMPOSE) --profile full down

infra-ps:
	$(COMPOSE) --profile full ps

infra-stats:      ## live memory footprint per container
	docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}'

run-%:            ## make run-gateway, make run-catalog-svc, ...
	./gradlew :services:$*:bootRun
