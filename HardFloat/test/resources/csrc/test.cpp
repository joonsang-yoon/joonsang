// include files are part of the g++ command line

#if VM_TRACE
#include "verilator.h"
#endif

int main(int argc, char* argv[]) {
  if (argc < 3) {
    printf("usage: %s <rounding-mode> <tininess-detection>\n", argv[0]);
    return -1;
  }

  dut module;
  size_t error = 0;
  size_t cnt = 0;

#if VM_TRACE
  VerilatedVcdFILE vcdfd(stderr);
  VerilatedVcdC tfp(&vcdfd);
  Verilated::traceEverOn(true);
  module.trace(&tfp, 99);
  tfp.open("");
#endif

  initialize_dut(module);
  module.ROUNDING_MODE = strtoull(argv[1], NULL, 16);
  module.DETECT_TININESS = strtoull(argv[2], NULL, 16);

  // reset
  for (size_t i = 0; i < 10; i++) {
    module.reset = 1;
    module.clock = 0;
    module.eval();
    module.clock = 1;
    module.eval();
  }
  module.reset = 0;

  // main operation
  for (size_t cycle = 0;; cycle++) {
    if (!process_inputs(module) || !process_outputs(module)) {
      printf("Ran %ld tests.\n", cnt);
      if (!error) fputs("No errors found.\n", stdout);
      break;
    }

    module.clock = 0;
    module.eval();

#if VM_TRACE
    tfp.dump(static_cast<vluint64_t>(cycle * 2));
#endif

    if (module.io_check) {
      if ((cnt % 10000 == 0) && cnt) printf("Ran %ld tests.\n", cnt);
      if (!module.io_pass) {
        error++;
        printf("[%07ld]", cnt);
#if defined(IS_DIV_OP)
        printf(" a=%s b=%s rm=%s dt=%s",
               VL_TO_STRING(module.io_output_a).c_str(),
               VL_TO_STRING(module.io_output_b).c_str(),
               VL_TO_STRING(module.io_output_roundingMode).c_str(),
               VL_TO_STRING(module.io_output_detectTininess).c_str());
#elif defined(IS_SQRT_OP)
        printf(" a=%s rm=%s dt=%s", VL_TO_STRING(module.io_output_a).c_str(),
               VL_TO_STRING(module.io_output_roundingMode).c_str(),
               VL_TO_STRING(module.io_output_detectTininess).c_str());
#endif
        printf("\n\t=> %s %s   expected: %s %s\n",
               VL_TO_STRING(module.io_actual_out).c_str(),
               VL_TO_STRING(module.io_actual_exceptionFlags).c_str(),
               VL_TO_STRING(module.io_expected_recOut).c_str(),
               VL_TO_STRING(module.io_expected_exceptionFlags).c_str());
        if (error == 20) {
          printf("Reached %ld errors. Aborting.\n", error);
          break;
        }
      }
      cnt++;
    }

    module.clock = 1;
    module.eval();

#if VM_TRACE
    tfp.dump(static_cast<vluint64_t>(cycle * 2 + 1));
#endif
  }

  return 0;
}
