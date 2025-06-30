import os
import numpy as np
import matplotlib.pyplot as plt
from matplotlib.ticker import FixedLocator, FuncFormatter
from matplotlib.colors import ListedColormap

# Configuration constants
S_BITS = 5
T_BITS = 8
S_FRACTIONAL_BITS = 4
T_FRACTIONAL_BITS = 4
S_RANGE = np.arange(0, 2 ** (S_BITS - 1) + 1)
T_RANGE = np.arange(-(2 ** (T_BITS - 1)), 2 ** (T_BITS - 1))

# Create a grid of integer S and T values
S, T = np.meshgrid(S_RANGE, T_RANGE)

# Define root digit conditions
ROOT_CONDITIONS = {
    "S": {
        "S_values": list(range(int(S_RANGE[-2] + 1) // 2, int(S_RANGE[-2] + 1))) + [2 ** (S_BITS - 1)],
        "s_conditions": [
            (2, lambda s, t: (s < 2 ** (S_BITS - 1)) & ((8 / 3) * (s + 1) <= t) & (t < (16 / 3) * (s + 1))),
            (1, lambda s, t: (s < 2 ** (S_BITS - 1)) & ((2 / 3) * (s + 1) <= t) & (t <= (10 / 3) * s - 2)),
            (
                0,
                lambda s, t: ((s < 2 ** (S_BITS - 1)) & (-(4 / 3) * (s - 1 / 48) <= t) & (t <= (4 / 3) * s - 2))
                | ((s == 2 ** (S_BITS - 1)) & (-(4 / 3) * (s - 1 / 3) <= t) & (t <= -1)),
            ),
            (
                -1,
                lambda s, t: ((s < 2 ** (S_BITS - 1)) & (-(10 / 3) * (s - 5 / 96) <= t) & (t <= -(2 / 3) * (s + 1) - 2))
                | ((s == 2 ** (S_BITS - 1)) & (-(10 / 3) * (s - 5 / 6) <= t) & (t <= -(2 / 3) * s - 2)),
            ),
            (
                -2,
                lambda s, t: ((s < 2 ** (S_BITS - 1)) & (-(16 / 3) * (s + 1) - 2 < t) & (t <= -(8 / 3) * (s + 1) - 2))
                | ((s == 2 ** (S_BITS - 1)) & (-(16 / 3) * s - 2 < t) & (t <= -(8 / 3) * s - 2)),
            ),
        ],
    },
}


def apply_conditions(S, T, conditions):
    result = np.full_like(S, np.nan, dtype=float)
    for case in conditions.values():
        s_mask = np.isin(S, case["S_values"])
        for s_value, condition in case["s_conditions"]:
            mask = s_mask & condition(S, T)
            result[mask] = s_value
    return result


def get_root_digit(S, T):
    return apply_conditions(S, T, ROOT_CONDITIONS)


def transform(x: int, y: int) -> tuple[int, int]:
    diff = x - y

    if diff == 0:
        if x % 2 == 1:
            return (x, x + 1)
        else:
            return (x - 1, x)

    if 1 <= diff <= 2:
        M = 4
    elif 3 <= diff <= 6:
        M = 8
    elif 7 <= diff <= 14:
        M = 16
    else:
        raise ValueError(f"The transformation for diff={diff} is not defined.")

    # base = 4a or 8a
    base = (y - 1) // M * M

    # threshold = 4a + 3 or 8a + 7
    threshold = base + M - 1

    # xp_low = 4a + 1 or 8a + 3, yp_low = 4a + 2 or 8a + 4
    xp_low = base + M // 2 - 1
    yp_low = base + M // 2

    # xp_high = 4a + 3 or 8a + 7, yp_high = 4a + 4 or 8a + 8
    xp_high = base + M - 1
    yp_high = base + M

    if x < threshold:
        return (xp_low, yp_low)
    else:
        return (xp_high, yp_high)


def remove_overlaps(s_list, min_t_list, max_t_list):
    for i in range(len(s_list) - 1):
        if s_list[i + 1] - s_list[i] == 0:
            x = max_t_list[i + 1]
            y = min_t_list[i]
            diff = x - y
            if diff == -1:
                continue
            try:
                new_x, new_y = transform(x, y)
                max_t_list[i + 1] = new_x
                min_t_list[i] = new_y
            except ValueError as e:
                print(f"Error at index {i}: {e}")
    return min_t_list, max_t_list


def get_root_digit_no_overlap(S, T, conditions):
    """Calculates root digits after removing overlaps."""
    result = np.full_like(S, np.nan, dtype=float)

    for case_name, case in conditions.items():
        for d_val in case["S_values"]:
            s_mask = S == d_val
            s_conditions = case["s_conditions"]

            # Store min and max T for each s at this S
            min_t_values = {}
            max_t_values = {}

            for s_value, condition in s_conditions:
                t_values = T[s_mask & condition(S, T)]
                if t_values.size > 0:
                    min_t_values[s_value] = np.min(t_values)
                    max_t_values[s_value] = np.max(t_values)

            # Sort s values based on sign of S
            sorted_s_values = sorted(min_t_values.keys(), reverse=(d_val >= 0))

            # Prepare lists for remove_overlaps
            s_list = [d_val] * len(sorted_s_values)
            min_t_list = [min_t_values[s] for s in sorted_s_values]
            max_t_list = [max_t_values[s] for s in sorted_s_values]

            # Remove overlaps
            min_t_list, max_t_list = remove_overlaps(s_list, min_t_list, max_t_list)
            print(s_list, min_t_list, max_t_list)

            # Apply the adjusted conditions
            for i, s_value in enumerate(sorted_s_values):
                mask = s_mask & (T >= min_t_list[i]) & (T <= max_t_list[i])
                result[mask] = s_value

    return result


def binary_formatter(bits, fractional_bits):
    def formatter(x, pos):
        if not np.isfinite(x):
            return "NaN"
        x_int = int(x)
        if x_int < 0:
            x_int = 2**bits + x_int
        binary_string = bin(x_int)[2:].zfill(bits)
        return f"{binary_string[:-fractional_bits]}.{binary_string[-fractional_bits:]}"

    return formatter


def create_custom_colormap():
    colors = [
        "#e6194b",  # Red ($s_{j+1} = 2$)
        "#ffe119",  # Yellow ($s_{j+1} = 1$)
        "#3cb44b",  # Green ($s_{j+1} = 0$)
        "#008080",  # Teal ($s_{j+1} = -1$)
        "#000080",  # Navy ($s_{j+1} = -2$)
    ]
    return ListedColormap(colors)


def plot_root_regions_no_overlap(S, T, s, quadrants):
    fig, ax = plt.subplots(figsize=(4, 100))
    cmap = create_custom_colormap()
    color_mapping = {
        2: cmap.colors[0],
        1: cmap.colors[1],
        0: cmap.colors[2],
        -1: cmap.colors[3],
        -2: cmap.colors[4],
    }

    if quadrants == "quadrants_1_4":
        quadrant_mask = S >= 2 ** (S_BITS - 2)
    elif quadrants == "quadrants_2_3":
        quadrant_mask = S < -(2 ** (S_BITS - 2))
    else:
        quadrant_mask = True

    s_masked = s[quadrant_mask]
    S_masked = S[quadrant_mask]
    T_masked = T[quadrant_mask]

    for s_val in sorted(color_mapping.keys(), reverse=True):
        mask = s_masked == s_val
        ax.scatter(
            S_masked[mask],
            T_masked[mask],
            c=color_mapping[s_val],
            label=f"$s_{{j+1}} = {s_val}$",
            marker="s",
            s=20,
            alpha=0.7,
            edgecolors="none",
            linewidths=0,
        )

    # Set equal aspect ratio
    ax.set_aspect("equal")

    ax.set_xlim(S_RANGE[0] - 1 / 3, S_RANGE[-1] + 1 / 3)
    ax.xaxis.set_major_locator(FixedLocator(np.arange(0, 2 ** (S_BITS - 1) + 1, 2 ** (S_FRACTIONAL_BITS - 2))))
    ax.yaxis.set_major_locator(
        FixedLocator(np.arange(-(2 ** (T_BITS - 1)), 2 ** (T_BITS - 1), 2 ** (T_FRACTIONAL_BITS - 1)))
    )

    ax.grid(True)
    ax.set_xlabel(r"$\sigma_j$")
    ax.set_ylabel(r"$\tau_j$")
    ax.set_title(r"$s_{j+1}$")
    if quadrants == "quadrants_1_4":
        ax.legend(loc="upper left")
    elif quadrants == "quadrants_2_3":
        ax.legend(loc="upper right")
    else:
        ax.legend(loc="upper center")
    ax.axhline(y=0, color="k", linestyle="--", alpha=0.3)

    ax.xaxis.set_major_formatter(FuncFormatter(binary_formatter(S_BITS, S_FRACTIONAL_BITS)))
    ax.yaxis.set_major_formatter(FuncFormatter(binary_formatter(T_BITS, T_FRACTIONAL_BITS)))

    plt.tight_layout()
    current_file_path = os.path.abspath(__file__)
    current_dir = os.path.dirname(current_file_path)
    save_path = os.path.abspath(os.path.join(current_dir, "../../figures/square_root"))
    os.makedirs(save_path, exist_ok=True)
    filename = f"radix4_rds_optimized_{quadrants}.pdf"
    full_save_path = os.path.join(save_path, filename)
    plt.savefig(full_save_path, dpi=600, format="pdf", bbox_inches="tight")
    plt.show()


def main():
    try:
        # Calculate root digits without overlaps
        s_no_overlap = get_root_digit_no_overlap(S, T, ROOT_CONDITIONS)

        # Plot the results for quadrants 1 and 4
        plot_root_regions_no_overlap(S, T, s_no_overlap, "quadrants_1_4")

    except Exception as e:
        print(f"An error occurred: {e}")


if __name__ == "__main__":
    main()
