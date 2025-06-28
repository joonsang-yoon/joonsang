SHELL := /usr/bin/env bash
.SHELLFLAGS := -eu -o pipefail -c

# Safer defaults:
# - delete partially-written targets on failure
# - disable old-style suffix rules (we don't rely on them)
.SUFFIXES:
.DELETE_ON_ERROR:

MAKEFILE_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
PARENT_DIR := $(abspath $(MAKEFILE_DIR)..)

# Allow running HardFloat tests standalone (without invoking the repo-root Makefile first).
TEST_OUTPUT_DIR ?= $(PARENT_DIR)/generated/test_artifacts
export TEST_OUTPUT_DIR

SOFTFLOAT_SUBMODULE := berkeley-softfloat-3
TESTFLOAT_SUBMODULE := berkeley-testfloat-3

SOFTFLOAT_DIR := $(MAKEFILE_DIR)$(SOFTFLOAT_SUBMODULE)
TESTFLOAT_DIR := $(MAKEFILE_DIR)$(TESTFLOAT_SUBMODULE)

# SoftFloat/TestFloat build directory (override if you are not on Linux x86_64)
SOFTFLOAT_PLATFORM ?= Linux-x86_64-GCC
TESTFLOAT_PLATFORM ?= Linux-x86_64-GCC

SOFTFLOAT_BUILD := $(SOFTFLOAT_DIR)/build/$(SOFTFLOAT_PLATFORM)
TESTFLOAT_BUILD := $(TESTFLOAT_DIR)/build/$(TESTFLOAT_PLATFORM)

SPECIALIZE_TYPE ?= RISCV

.DEFAULT_GOAL := help

.PHONY: help submodules test clean distclean

help: ## Show this help
	@echo "Usage:"
	@echo "  make <target> [VARIABLE=value]"
	@echo ""
	@echo "Common variables:"
	@echo "  TEST_OUTPUT_DIR=$(TEST_OUTPUT_DIR)"
	@echo "  SOFTFLOAT_PLATFORM=$(SOFTFLOAT_PLATFORM)"
	@echo "  TESTFLOAT_PLATFORM=$(TESTFLOAT_PLATFORM)"
	@echo "  SPECIALIZE_TYPE=$(SPECIALIZE_TYPE)"
	@echo ""
	@echo "Targets:"
	@awk 'BEGIN {FS = ":.*##"} /^[a-zA-Z0-9_.-]+:.*##/ { t[++n] = $$1; d[n] = $$2; if (length($$1) > m) m = length($$1) } END { for (i=1; i<=n; i++) printf "  %-" m "s %s\n", t[i], d[i] }' $(MAKEFILE_LIST)
	@echo ""

submodules: ## Initialize/update git submodules (recursive)
	git -C "$(MAKEFILE_DIR)" submodule update --init --recursive

# Build Berkeley TestFloat generator and run Scala/Verilator-based HardFloat verification.
# NOTE: testfloat_gen is executed by the Scala tests, so we prepend TESTFLOAT_BUILD to PATH.
test: $(TESTFLOAT_BUILD)/testfloat_gen ## Run HardFloat verification (Scala/Verilator)
	@echo "[HardFloat] TEST_OUTPUT_DIR=$(TEST_OUTPUT_DIR)"
	PATH="$(TESTFLOAT_BUILD):$(PATH)" $(MAKE) -C "$(PARENT_DIR)" _test-hardfloat

clean: ## Clean SoftFloat/TestFloat build products (keeps sources/submodules intact)
	@if [ -f "$(SOFTFLOAT_BUILD)/Makefile" ]; then $(MAKE) -C "$(SOFTFLOAT_BUILD)" clean; fi
	@if [ -f "$(TESTFLOAT_BUILD)/Makefile" ]; then $(MAKE) -C "$(TESTFLOAT_BUILD)" clean; fi

distclean: clean ## Deep clean (git clean -fdx) this directory and its submodules
	@echo "[HardFloat] Running: git clean -fdx (this will remove ALL untracked files under HardFloat/)"
	git -C "$(MAKEFILE_DIR)" clean -fdx
	git -C "$(MAKEFILE_DIR)" submodule foreach git clean -fdx || true

$(SOFTFLOAT_DIR)/.git:
	git -C "$(MAKEFILE_DIR)" submodule update --init --recursive $(SOFTFLOAT_SUBMODULE)

$(TESTFLOAT_DIR)/.git:
	git -C "$(MAKEFILE_DIR)" submodule update --init --recursive $(TESTFLOAT_SUBMODULE)

$(SOFTFLOAT_BUILD)/softfloat.a: $(SOFTFLOAT_DIR)/.git
	$(MAKE) -C "$(SOFTFLOAT_BUILD)" SPECIALIZE_TYPE="$(SPECIALIZE_TYPE)"

$(TESTFLOAT_BUILD)/testfloat_gen: $(TESTFLOAT_DIR)/.git $(SOFTFLOAT_BUILD)/softfloat.a
	$(MAKE) -C "$(TESTFLOAT_BUILD)" SPECIALIZE_TYPE="$(SPECIALIZE_TYPE)"
