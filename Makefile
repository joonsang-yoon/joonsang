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
TEST_OUTPUT_DIR ?= $(GEN_DIR)/test_artifacts
export TEST_OUTPUT_DIR

# Git submodules
ROCKET_CHIP_DIR := $(MAKEFILE_DIR)rocket-chip

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

# Scala formatting: format only our project modules (avoid formatting submodules like rocket-chip).
FORMAT_MODULES_QUERY = {TopLevelModule,ExternalModule,HardFloat,HardInt,HardUtils}

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
#   HardFloat.AddRecFN(11, 53)          -> HardFloat/AddRecFN_11_53
#   HardInt.Radix4SRTDivider(64)        -> HardInt/Radix4SRTDivider_64
define module_to_path
$(strip $(subst $(space),,$(subst $(comma),_,$(subst $(rparen),,$(subst $(lparen),_,$(subst .,/,$(1)))))))
endef

MODULE_PATH := $(call module_to_path,$(MODULE))
TARGET_DIR ?= $(VERILOG_OUTPUT_DIR)/$(MODULE_PATH)

# ----------------------------
# Targets
# ----------------------------
.PHONY: help submodules verilog test test-hardfloat test-hardint reformat check-format clean distclean elaborate-help
# Internal targets
.PHONY: _test-hardfloat

help: ## Show this help
	@echo "Usage:"
	@echo "  make <target> [VARIABLE=value]"
	@echo ""
	@echo "Common variables:"
	@echo "  MODULE=$(MODULE)"
	@echo "  TARGET_DIR=$(TARGET_DIR)"
	@echo "  TEST_OUTPUT_DIR=$(TEST_OUTPUT_DIR)"
	@echo "  MILL_JOBS=$(MILL_JOBS)"
	@echo ""
	@echo "Targets:"
	@awk 'BEGIN {FS = ":.*##"} /^[a-zA-Z0-9_.-]+:.*##/ { t[++n] = $$1; d[n] = $$2; if (length($$1) > m) m = length($$1) } END { for (i=1; i<=n; i++) printf "  %-" m "s %s\n", t[i], d[i] }' $(MAKEFILE_LIST)
	@echo ""
	@echo "Examples:"
	@echo "  make submodules"
	@echo "  make verilog MODULE=TopLevelModule.CustomDesign"
	@echo "  make verilog MODULE=ExternalModule.AnotherCustomDesign"
	@echo "  make verilog MODULE='HardFloat.AddRecFN(11, 53)'"
	@echo "  make verilog MODULE='HardInt.Radix4SRTDivider(64)'"
	@echo "  make test"

submodules: ## Initialize/update git submodules (recursive)
	git -C "$(MAKEFILE_DIR)" submodule update --init --recursive

# Ensure rocket-chip is present for targets that compile code.
$(ROCKET_CHIP_DIR)/.git:
	git -C "$(MAKEFILE_DIR)" submodule update --init --recursive rocket-chip

# Targets that need rocket-chip on disk.
verilog test test-hardfloat test-hardint reformat check-format elaborate-help _test-hardfloat: | $(ROCKET_CHIP_DIR)/.git

verilog: ## Generate SystemVerilog for MODULE (defaults shown in `make help`)
	@mkdir -p "$(TARGET_DIR)"
	$(MILL_CMD) $(PROJECT).runMain Elaborate "$(MODULE)" --target-dir "$(TARGET_DIR)"
	@echo "Generated SystemVerilog in: $(TARGET_DIR)"

test: ## Run all tests (TopLevelModule + HardFloat + HardInt)
	$(MILL_CMD) $(PROJECT).test
	$(MAKE) -C "$(MAKEFILE_DIR)HardFloat" test
	$(MILL_CMD) HardInt.test

test-hardfloat: ## Run HardFloat test suite only
	$(MAKE) -C "$(MAKEFILE_DIR)HardFloat" test

test-hardint: ## Run HardInt test suite only
	$(MILL_CMD) HardInt.test

# Internal target for HardFloat tests (invoked from HardFloat/Makefile)
_test-hardfloat:
	$(MILL_CMD) HardFloat.test

reformat: ## Reformat Scala sources (scalafmt via Mill)
	$(MILL_CMD) "$(FORMAT_MODULES_QUERY).reformat"

check-format: ## Check Scala formatting (CI-friendly)
	$(MILL_CMD) "$(FORMAT_MODULES_QUERY).checkFormat"

clean: ## Remove generated Verilog files and test artifacts
	rm -rf "$(GEN_DIR)"

distclean: clean ## Remove all build artifacts (including Mill out/) and deep-clean HardFloat submodules
	rm -rf "$(MAKEFILE_DIR)out"
	$(MAKE) -C "$(MAKEFILE_DIR)HardFloat" distclean || true

elaborate-help: ## Show full Elaborate/ChiselStage options (used by `make verilog`)
	@$(MILL_CMD) $(PROJECT).runMain Elaborate --help
