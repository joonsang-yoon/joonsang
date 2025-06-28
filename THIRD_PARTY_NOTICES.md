# Third-Party Notices

This repository is primarily licensed under the **Apache License 2.0** (see `LICENSE`), except where a file, directory, or submodule is identified as third-party code and covered by its own license terms.

This document is an attribution summary for code redistributed **in-tree** or referenced as a **pinned git submodule**. It is provided for convenience only and does **not** replace or modify any component's original license text.

## Scope

This file is intended to cover:

- third-party code copied into this repository and kept under its original terms;
- third-party repositories brought in via git submodules; and
- the local paths where maintainers and redistributors can find the relevant license text.

If there is any conflict between this summary and a component's own license file, the component's own license file controls.

## In-tree third-party / derived code

### Berkeley HardFloat

- **Local paths:** primarily `HardFloat/src/`, `HardFloat/test/`, and related `HardFloat/test/resources/` content
- **Upstream project:** [Berkeley HardFloat](https://github.com/ucb-bar/berkeley-hardfloat)
- **Local license file:** `HardFloat/LICENSE`
- **License summary:** Regents of the University of California permissive license (BSD-3-Clause-style)

This repository includes source code derived from Berkeley HardFloat. Where applicable, original copyright notices and license terms are retained in the copied or adapted codebase.

**Important scope note:** this notice is meant to cover the Berkeley HardFloat-derived code in the `HardFloat` implementation and verification tree. Repository-authored material under `HardFloat/docs/research/` is not treated as Berkeley HardFloat-derived solely because it lives under the same top-level directory, unless a file says otherwise.

## Third-party git submodules

### Berkeley SoftFloat Release 3

- **Local path:** `HardFloat/berkeley-softfloat-3`
- **Upstream project:** [berkeley-softfloat-3](https://github.com/ucb-bar/berkeley-softfloat-3)
- **Pinned revision (gitlink):** `5c06db33fc1e2130f67c045327b0ec949032df1d`
- **Local license file after submodule checkout:** `HardFloat/berkeley-softfloat-3/COPYING.txt`

This submodule is used by the floating-point verification flow.

### Berkeley TestFloat Release 3

- **Local path:** `HardFloat/berkeley-testfloat-3`
- **Upstream project:** [berkeley-testfloat-3](https://github.com/ucb-bar/berkeley-testfloat-3)
- **Pinned revision (gitlink):** `06b20075dd3c1a5d0dd007a93643282832221612`
- **Local license file after submodule checkout:** `HardFloat/berkeley-testfloat-3/COPYING.txt`

This submodule is used by the floating-point verification flow, including builds of `testfloat_gen` for HardFloat testing.

## Repository-owned code

Unless a path above or an in-file notice states otherwise, code and documentation added directly to this repository remain under the repository's default license, the **Apache License 2.0**.

That includes project-specific material such as:

- repository build and workflow files;
- project-authored modules outside identified third-party code;
- local documentation and reference notes; and
- locally authored research/tutorial material such as `HardFloat/docs/research/digit_recurrence/`, unless a specific file states different terms.

## Submodule checkout note

Some license paths listed above exist only after submodules are fetched. For a complete working tree, initialize submodules with:

```bash
git submodule update --init --recursive
```

## Maintainer guidance

When adding new vendored code or third-party submodules, update this file with:

1. the local path;
2. the upstream project or repository;
3. the pinned revision, if applicable;
4. the local license-file path; and
5. a short note explaining whether the code is copied in-tree or brought in as a submodule.

If you believe any attribution is missing or incorrect, please open an issue or submit a pull request so it can be fixed.
