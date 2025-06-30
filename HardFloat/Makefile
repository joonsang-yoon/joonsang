MAKEFILE_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
SOFTFLOAT_DIR := $(MAKEFILE_DIR)berkeley-softfloat-3
TESTFLOAT_DIR := $(MAKEFILE_DIR)berkeley-testfloat-3
SOFTFLOAT_BUILD := $(SOFTFLOAT_DIR)/build/Linux-x86_64-GCC
TESTFLOAT_BUILD := $(TESTFLOAT_DIR)/build/Linux-x86_64-GCC
PARENT_DIR := $(MAKEFILE_DIR)..

.DEFAULT_GOAL := test

.PHONY: test clean

test: $(TESTFLOAT_BUILD)/testfloat_gen
	export PATH="$(PATH):$(TESTFLOAT_BUILD)" && $(MAKE) -C $(PARENT_DIR) _test-hardfloat

clean:
	cd $(MAKEFILE_DIR) && git clean -fdx
	cd $(MAKEFILE_DIR) && git submodule foreach git clean -fdx

$(SOFTFLOAT_DIR)/.git:
	cd $(MAKEFILE_DIR) && git submodule update --init berkeley-softfloat-3

$(TESTFLOAT_DIR)/.git:
	cd $(MAKEFILE_DIR) && git submodule update --init berkeley-testfloat-3

$(SOFTFLOAT_BUILD)/softfloat.a: $(SOFTFLOAT_DIR)/.git
	$(MAKE) -C $(SOFTFLOAT_BUILD) SPECIALIZE_TYPE=RISCV

$(TESTFLOAT_BUILD)/testfloat_gen: $(TESTFLOAT_DIR)/.git $(SOFTFLOAT_BUILD)/softfloat.a
	$(MAKE) -C $(TESTFLOAT_BUILD) SPECIALIZE_TYPE=RISCV
