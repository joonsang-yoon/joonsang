// Exhaustive test for Radix4SRTDivider
// Drives all combinations of (isSigned, dividend, divisor) => 2^(2W+1) tests.
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

static inline int32_t sextN(uint32_t x) {
  int32_t s = static_cast<int32_t>(x & MASK);
  if (s & SIGN) s -= static_cast<int32_t>(1u << W);
  return s;
}

static inline uint16_t packN(int32_t s) {
  return static_cast<uint16_t>(static_cast<uint32_t>(s) & MASK);
}

static inline void compute_expected(uint32_t dividend, uint32_t divisor,
                                    bool isSigned, uint16_t& q, uint16_t& r) {
  if (!isSigned) {
    if ((divisor & MASK) == 0) {
      q = static_cast<uint16_t>(MASK);             // DIVU x/0 -> all ones
      r = static_cast<uint16_t>(dividend & MASK);  // REMU x/0 -> dividend
      return;
    }
    uint32_t a = dividend & MASK;
    uint32_t b = divisor & MASK;
    q = static_cast<uint16_t>((a / b) & MASK);
    r = static_cast<uint16_t>((a % b) & MASK);
  } else {
    int32_t a = sextN(dividend);
    int32_t b = sextN(divisor);
    if (b == 0) {
      q = static_cast<uint16_t>(MASK);             // DIV x/0 -> -1 (all ones)
      r = static_cast<uint16_t>(dividend & MASK);  // REM x/0 -> dividend
      return;
    }
    const int32_t SMIN = -(1 << (W - 1));  // -2^(W-1)
    if (a == SMIN && b == -1) {
      // RISC-V DIV overflow: q = -2^(W-1), r = 0
      q = packN(SMIN);
      r = 0;
      return;
    }
    int32_t qq = a / b;  // trunc toward zero
    int32_t rr = a % b;  // same sign as dividend
    q = packN(qq);
    r = packN(rr);
  }
}

struct Expect {
  uint16_t q, r;
  uint8_t isSigned;
  uint16_t dividend, divisor;
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
  uint32_t isSigned = 0;
  uint32_t dividend = 0;
  uint32_t divisor = 0;

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

  // Helper to advance enumeration (isSigned x dividend x divisor)
  auto advance = [&]() {
    if (++divisor < LIM) return true;
    divisor = 0;
    if (++dividend < LIM) return true;
    dividend = 0;
    if (++isSigned < 2u) return true;
    return false;  // done
  };

  bool done_issuing = false;

  // Prime first input
  module.io_req_bits_data_dividendSign = (isSigned & 1u) && (dividend & SIGN);
  module.io_req_bits_data_divisorSign = (isSigned & 1u) && (divisor & SIGN);
  module.io_req_bits_data_dividend = dividend & MASK;
  module.io_req_bits_data_divisor = divisor & MASK;

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
      uint16_t exp_q = 0, exp_r = 0;
      compute_expected(dividend, divisor, (isSigned & 1u) != 0, exp_q, exp_r);
      expQ.push_back(Expect{exp_q, exp_r, static_cast<uint8_t>(isSigned & 1u),
                            static_cast<uint16_t>(dividend & MASK),
                            static_cast<uint16_t>(divisor & MASK)});
      issued++;

      // Advance to next combo or finish issuing
      if (!advance() || (issued >= max_tests)) {
        done_issuing = true;
      }

      // Update next input pins
      module.io_req_bits_data_dividendSign =
          (isSigned & 1u) && (dividend & SIGN);
      module.io_req_bits_data_divisorSign = (isSigned & 1u) && (divisor & SIGN);
      module.io_req_bits_data_dividend = dividend & MASK;
      module.io_req_bits_data_divisor = divisor & MASK;
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

      uint16_t got_q =
          static_cast<uint16_t>(module.io_resp_bits_data_quotient & MASK);
      uint16_t got_r =
          static_cast<uint16_t>(module.io_resp_bits_data_remainder & MASK);

      if (got_q != e.q || got_r != e.r) {
        errors++;
        fprintf(
            stderr,
            "[%#012llx] ERROR isSigned=%u dividend=%#06x divisor=%#06x -> got "
            "q=%#06x r=%#06x, expected q=%#06x r=%#06x\n",
            (unsigned long long)checked, e.isSigned, e.dividend, e.divisor,
            got_q, got_r, e.q, e.r);
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
