SHELL := /usr/bin/env bash
.SHELLFLAGS := -eu -o pipefail -c

# Safer defaults:
# - delete partially-written targets on failure
# - disable old-style suffix rules (we don't rely on them)
.SUFFIXES:
.DELETE_ON_ERROR:

MAKEFILE_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))

# Output directories
GEN_DIR ?= $(MAKEFILE_DIR)generated
VERILOG_OUTPUT_DIR ?= $(GEN_DIR)/verilog

# Mill / project configuration
#
# PROJECT is the Mill module that contains the `Elaborate` entrypoint.
# (Default: TopLevelModule, where TopLevelModule/src/Elaborate.scala lives.)
PROJECT ?= TopLevelModule
MODULE ?= TopLevelModule.CustomDesign

MILL ?= $(MAKEFILE_DIR)mill
# Default parallelism: number of online CPUs (override with MILL_JOBS=...)
MILL_JOBS ?= $(shell getconf _NPROCESSORS_ONLN 2>/dev/null || nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 1)
MILL_OPTS ?= -i -j $(MILL_JOBS)
MILL_CMD := $(MILL) $(MILL_OPTS)

.DEFAULT_GOAL := help

# ----------------------------
# Helpers
# ----------------------------
empty :=
space := $(empty) $(empty)
comma := ,
lparen := (
rparen := )

# Convert MODULE spec into a filesystem-friendly path.
# Examples:
#   TopLevelModule.CustomDesign         -> TopLevelModule/CustomDesign
#   ExternalModule.AnotherCustomDesign  -> ExternalModule/AnotherCustomDesign
define module_to_path
$(strip $(subst $(space),,$(subst $(comma),_,$(subst $(rparen),,$(subst $(lparen),_,$(subst .,/,$(1)))))))
endef

MODULE_PATH := $(call module_to_path,$(MODULE))
TARGET_DIR ?= $(VERILOG_OUTPUT_DIR)/$(MODULE_PATH)

# ----------------------------
# Targets
# ----------------------------
.PHONY: help verilog test reformat check-format clean distclean elaborate-help

help: ## Show this help
	@echo "Usage:"
	@echo "  make <target> [VARIABLE=value]"
	@echo ""
	@echo "Common variables:"
	@echo "  MODULE=$(MODULE)"
	@echo "  TARGET_DIR=$(TARGET_DIR)"
	@echo "  MILL_JOBS=$(MILL_JOBS)"
	@echo ""
	@echo "Targets:"
	@awk 'BEGIN {FS = ":.*##"} /^[a-zA-Z0-9_.-]+:.*##/ { t[++n] = $$1; d[n] = $$2; if (length($$1) > m) m = length($$1) } END { for (i=1; i<=n; i++) printf "  %-" m "s %s\n", t[i], d[i] }' $(MAKEFILE_LIST)
	@echo ""
	@echo "Examples:"
	@echo "  make verilog MODULE=TopLevelModule.CustomDesign"
	@echo "  make verilog MODULE=ExternalModule.AnotherCustomDesign"
	@echo "  make test"

verilog: ## Generate SystemVerilog for MODULE (defaults shown in `make help`)
	@mkdir -p "$(TARGET_DIR)"
	$(MILL_CMD) $(PROJECT).runMain Elaborate "$(MODULE)" --target-dir "$(TARGET_DIR)"
	@echo "Generated SystemVerilog in: $(TARGET_DIR)"

test: ## Run all tests
	$(MILL_CMD) $(PROJECT).test

reformat: ## Reformat Scala sources (scalafmt via Mill)
	$(MILL_CMD) __.reformat

check-format: ## Check Scala formatting (CI-friendly)
	$(MILL_CMD) __.checkFormat

clean: ## Remove generated Verilog files
	rm -rf "$(GEN_DIR)"

distclean: clean ## Remove all build artifacts (including Mill out/)
	rm -rf "$(MAKEFILE_DIR)out"

elaborate-help: ## Show full Elaborate/ChiselStage options (used by `make verilog`)
	@$(MILL_CMD) $(PROJECT).runMain Elaborate --help
