// Exhaustive test for Radix4BoothMultiplier
// Drives all combinations of (isMultiplicandSigned, isMultiplierSigned,
// multiplicand, multiplier) => 2^(2W+2) tests.
// You can set env MAX_TESTS to limit the run for quick sanity checks.
// Build/run is managed by HardIntTester.scala.

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#include <deque>
#include <limits>

#include "dut.h"  // verilator --prefix=dut

#if VM_TRACE
#include "verilator.h"
#endif

// Optional progress verbosity
static bool verbose = true;

static inline void tick(dut& m, uint64_t& cycle
#if VM_TRACE
                        ,
                        VerilatedVcdC* tfp
#endif
) {
  // falling edge
  m.clock = 0;
  m.eval();
#if VM_TRACE
  if (tfp) tfp->dump(static_cast<vluint64_t>(cycle * 2));
#endif

  // rising edge
  m.clock = 1;
  m.eval();
#if VM_TRACE
  if (tfp) tfp->dump(static_cast<vluint64_t>(cycle * 2 + 1));
#endif

  cycle++;
}

#ifndef W
#error "W must be defined via compiler flag (e.g., -DW=15)"
#endif

namespace {
constexpr uint32_t MASK = (1u << W) - 1u;
constexpr uint32_t SIGN = (1u << (W - 1));
constexpr uint32_t LIM = (1u << W);
constexpr uint64_t MASK2W = (1ULL << (2 * W)) - 1ULL;

static inline int64_t sextN(uint32_t x) {
  int32_t s = static_cast<int32_t>(x & MASK);
  if (s & SIGN) s -= static_cast<int32_t>(1u << W);
  return static_cast<int64_t>(s);
}

static inline uint64_t zextN(uint32_t x) {
  return static_cast<uint64_t>(x & MASK);
}

static inline uint32_t pack2N(int64_t p) {
  return static_cast<uint32_t>(static_cast<uint64_t>(p) & MASK2W);
}

static inline void compute_expected(uint32_t multiplicand, uint32_t multiplier,
                                    bool isMultiplicandSigned,
                                    bool isMultiplierSigned, uint32_t& prod2W) {
  int64_t a = isMultiplicandSigned ? sextN(multiplicand)
                                   : static_cast<int64_t>(zextN(multiplicand));
  int64_t b = isMultiplierSigned ? sextN(multiplier)
                                 : static_cast<int64_t>(zextN(multiplier));
  int64_t p = a * b;  // fits comfortably in int64 for W <= 16
  prod2W = pack2N(p);
}

struct Expect {
  uint32_t prod2W;
  uint8_t isMultiplicandSigned;
  uint8_t isMultiplierSigned;
  uint16_t multiplicand, multiplier;
};
}  // namespace

int main(int argc, char* argv[]) {
  dut module;
  uint64_t cycle = 0;

#if VM_TRACE
  VerilatedVcdFILE vcdfd(stderr);
  VerilatedVcdC tfp(&vcdfd);
  Verilated::traceEverOn(true);
  module.trace(&tfp, 99);
  tfp.open("");
  VerilatedVcdC* tfp_ptr = &tfp;
#else
  void* tfp_ptr = nullptr;
#endif

  // Reset
  module.reset = 1;
  module.io_req_valid = 0;
  module.io_resp_ready = 0;
  for (int i = 0; i < 10; i++) {
    tick(module, cycle
#if VM_TRACE
         ,
         &tfp
#endif
    );
  }
  module.reset = 0;

  // Always ready to consume responses
  module.io_resp_ready = 1;
  module.eval();

  // Exhaustive enumeration state
  uint32_t isMultiplicandSigned = 0;
  uint32_t isMultiplierSigned = 0;
  uint32_t multiplicand = 0;
  uint32_t multiplier = 0;

  // MAX_TESTS escape hatch
  uint64_t max_tests = std::numeric_limits<uint64_t>::max();
  if (const char* mt = getenv("MAX_TESTS")) {
    max_tests = strtoull(mt, nullptr, 10);
    if (max_tests == 0) max_tests = 1;
  }

  // Work queue for expected results (module is pipelined)
  std::deque<Expect> expQ;

  uint64_t issued = 0;   // number of requests sent
  uint64_t checked = 0;  // number of responses checked
  uint64_t errors = 0;

  // Helper to advance enumeration (isMultiplicandSigned x isMultiplierSigned x
  // multiplicand x multiplier)
  auto advance = [&]() {
    if (++multiplier < LIM) return true;
    multiplier = 0;
    if (++multiplicand < LIM) return true;
    multiplicand = 0;
    if (++isMultiplierSigned < 2u) return true;
    isMultiplierSigned = 0;
    if (++isMultiplicandSigned < 2u) return true;
    return false;  // done
  };

  bool done_issuing = false;

  // Prime first input
  module.io_req_bits_data_isMultiplicandSigned = isMultiplicandSigned & 1u;
  module.io_req_bits_data_isMultiplierSigned = isMultiplierSigned & 1u;
  module.io_req_bits_data_multiplicand = multiplicand & MASK;
  module.io_req_bits_data_multiplier = multiplier & MASK;

  while (!done_issuing || !expQ.empty()) {
    // Drive valid when we still have inputs to send and under MAX_TESTS
    bool can_issue_more = !done_issuing && (issued < max_tests);
    module.io_req_valid = can_issue_more ? 1 : 0;

    // Pre-sample fire on req (combinational ready + our valid)
    bool will_fire_req = module.io_req_valid && module.io_req_ready;

    // Tick
    tick(module, cycle
#if VM_TRACE
         ,
         &tfp
#endif
    );

    // If request fired, compute and enqueue expected, and advance inputs
    if (will_fire_req) {
      uint32_t exp_p = 0;
      compute_expected(multiplicand, multiplier,
                       (isMultiplicandSigned & 1u) != 0,
                       (isMultiplierSigned & 1u) != 0, exp_p);
      expQ.push_back(Expect{exp_p,
                            static_cast<uint8_t>(isMultiplicandSigned & 1u),
                            static_cast<uint8_t>(isMultiplierSigned & 1u),
                            static_cast<uint16_t>(multiplicand & MASK),
                            static_cast<uint16_t>(multiplier & MASK)});
      issued++;

      // Advance to next combo or finish issuing
      if (!advance() || (issued >= max_tests)) {
        done_issuing = true;
      }

      // Update next input pins
      module.io_req_bits_data_isMultiplicandSigned = isMultiplicandSigned & 1u;
      module.io_req_bits_data_isMultiplierSigned = isMultiplierSigned & 1u;
      module.io_req_bits_data_multiplicand = multiplicand & MASK;
      module.io_req_bits_data_multiplier = multiplier & MASK;
    }

    // Check response if valid
    if (module.io_resp_valid && module.io_resp_ready) {
      if (expQ.empty()) {
        fprintf(stderr,
                "Internal error: response with empty expectation queue.\n");
        errors++;
        break;
      }
      Expect e = expQ.front();
      expQ.pop_front();

      uint32_t got_p =
          static_cast<uint32_t>(module.io_resp_bits_data_product & MASK2W);

      if (got_p != e.prod2W) {
        errors++;
        fprintf(stderr,
                "[%#012llx] ERROR isMultiplicandSigned=%u "
                "isMultiplierSigned=%u multiplicand=%#06x "
                "multiplier=%#06x -> got p=%#010x, expected p=%#010x\n",
                (unsigned long long)checked, e.isMultiplicandSigned,
                e.isMultiplierSigned, e.multiplicand, e.multiplier, got_p,
                e.prod2W);
        if (errors >= 20) {
          fprintf(stderr, "Reached %llu errors. Aborting.\n",
                  (unsigned long long)errors);
          break;
        }
      }

      checked++;
      if (verbose && ((checked & ((1ULL << 20) - 1)) == 0ULL)) {
        printf("Checked %#llx tests...\n", (unsigned long long)checked);
        fflush(stdout);
      }
    }
  }

  printf("Ran %llu tests.\n", (unsigned long long)checked);
  if (errors == 0) {
    fputs("No errors found.\n", stdout);
  }

#if VM_TRACE
  tfp.close();
#endif
  return errors ? 1 : 0;
}
