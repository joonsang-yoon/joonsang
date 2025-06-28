MAKEFILE_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
VERILOG_OUTPUT_DIR := $(MAKEFILE_DIR)generated/verilog

PROJECT := TopLevelModule
MODULE ?= TopLevelModule.CustomDesign
MILL := $(MAKEFILE_DIR)mill -i

.DEFAULT_GOAL := help

.PHONY: verilog test reformat check-format clean help

verilog:
	@if [ -z "$(MODULE)" ]; then \
		echo "Error: MODULE not specified"; \
		echo "Usage: make verilog MODULE=<ModuleName>"; \
		exit 1; \
	fi
	@MODULE_PATH="$$(echo '$(MODULE)' | sed 's/\./\//g; s/(/\_/g; s/)//g; s/,/_/g')"; \
	TARGET_DIR="$(VERILOG_OUTPUT_DIR)/$$MODULE_PATH"; \
	mkdir -p "$$TARGET_DIR"; \
	$(MILL) $(PROJECT).runMain Elaborate "$(MODULE)" --target-dir "$$TARGET_DIR"

test:
	$(MILL) $(PROJECT).test

reformat:
	$(MILL) __.reformat

check-format:
	$(MILL) __.checkFormat

clean:
	-rm -rf $(VERILOG_OUTPUT_DIR)

help:
	@echo "================================ AVAILABLE COMMANDS ================================"
	@echo "verilog      : Generate SystemVerilog for specified module"
	@echo "               Usage: make verilog MODULE=<ModuleName>"
	@echo "               Default: MODULE=$(MODULE)"
	@echo "               Examples:"
	@echo "                 make verilog MODULE=TopLevelModule.CustomDesign"
	@echo "                 make verilog MODULE=ExternalModule.AnotherCustomDesign"
	@echo "test         : Run all tests"
	@echo "reformat     : Reformat all source files"
	@echo "check-format : Check formatting of all source files"
	@echo "clean        : Remove generated files and directories"
	@echo "help         : Display this help message"
	@echo ""
	@echo "================= ELABORATE OPTIONS (for verilog target) ================="
	@$(MILL) $(PROJECT).runMain Elaborate --help
