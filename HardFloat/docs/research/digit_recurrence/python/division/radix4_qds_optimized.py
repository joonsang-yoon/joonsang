import os
import numpy as np
import matplotlib.pyplot as plt
from matplotlib.ticker import FixedLocator, FuncFormatter
from matplotlib.colors import ListedColormap

# Configuration constants
D_BITS = 5
T_BITS = 8
D_FRACTIONAL_BITS = 4
T_FRACTIONAL_BITS = 5
D_RANGE = np.arange(-(2 ** (D_BITS - 1)), 2 ** (D_BITS - 1))
T_RANGE = np.arange(-(2 ** (T_BITS - 1)), 2 ** (T_BITS - 1))

# Create a grid of integer D and T values
D, T = np.meshgrid(D_RANGE, T_RANGE)

# Define quotient digit conditions
QUOTIENT_CONDITIONS = {
    "positive_D": {
        "D_values": list(range(int(D_RANGE[-1] + 1) // 2, int(D_RANGE[-1] + 1))),
        "q_conditions": [
            (2, lambda d, t: ((8 / 3) * (d + 1) <= t) & (t < (16 / 3) * (d + 1))),
            (1, lambda d, t: ((2 / 3) * (d + 1) <= t) & (t <= (10 / 3) * d - 2)),
            (0, lambda d, t: (-(4 / 3) * d <= t) & (t <= (4 / 3) * d - 2)),
            (-1, lambda d, t: (-(10 / 3) * d <= t) & (t <= -(2 / 3) * (d + 1) - 2)),
            (-2, lambda d, t: (-(16 / 3) * (d + 1) - 2 < t) & (t <= -(8 / 3) * (d + 1) - 2)),
        ],
    },
    "negative_D": {
        "D_values": list(range(int(D_RANGE[0]), int(D_RANGE[0]) // 2)),
        "q_conditions": [
            (2, lambda d, t: ((16 / 3) * d - 2 < t) & (t <= (8 / 3) * d - 2)),
            (1, lambda d, t: ((10 / 3) * (d + 1) <= t) & (t <= (2 / 3) * d - 2)),
            (0, lambda d, t: ((4 / 3) * (d + 1) <= t) & (t <= -(4 / 3) * (d + 1) - 2)),
            (-1, lambda d, t: (-(2 / 3) * d <= t) & (t <= -(10 / 3) * (d + 1) - 2)),
            (-2, lambda d, t: (-(8 / 3) * d <= t) & (t <= -(16 / 3) * d)),
        ],
    },
}


def apply_conditions(D, T, conditions):
    result = np.full_like(D, np.nan, dtype=float)
    for case in conditions.values():
        d_mask = np.isin(D, case["D_values"])
        for q_value, condition in case["q_conditions"]:
            mask = d_mask & condition(D, T)
            result[mask] = q_value
    return result


def get_quotient_digit(D, T):
    return apply_conditions(D, T, QUOTIENT_CONDITIONS)


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


def remove_overlaps(d_list, min_t_list, max_t_list):
    for i in range(len(d_list) - 1):
        if d_list[i + 1] - d_list[i] == 0:
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


def get_quotient_digit_no_overlap(D, T, conditions):
    """Calculates quotient digits after removing overlaps."""
    result = np.full_like(D, np.nan, dtype=float)

    for case_name, case in conditions.items():
        for d_val in case["D_values"]:
            d_mask = D == d_val
            q_conditions = case["q_conditions"]

            # Store min and max T for each q at this D
            min_t_values = {}
            max_t_values = {}

            for q_value, condition in q_conditions:
                t_values = T[d_mask & condition(D, T)]
                if t_values.size > 0:
                    min_t_values[q_value] = np.min(t_values)
                    max_t_values[q_value] = np.max(t_values)

            # Sort q values based on sign of D
            sorted_q_values = sorted(min_t_values.keys(), reverse=(d_val >= 0))

            # Prepare lists for remove_overlaps
            d_list = [d_val] * len(sorted_q_values)
            min_t_list = [min_t_values[q] for q in sorted_q_values]
            max_t_list = [max_t_values[q] for q in sorted_q_values]

            # Remove overlaps
            min_t_list, max_t_list = remove_overlaps(d_list, min_t_list, max_t_list)
            print(d_list, min_t_list, max_t_list)

            # Apply the adjusted conditions
            for i, q_value in enumerate(sorted_q_values):
                mask = d_mask & (T >= min_t_list[i]) & (T <= max_t_list[i])
                result[mask] = q_value

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
        "#e6194b",  # Red ($q_{j+1} = 2$)
        "#ffe119",  # Yellow ($q_{j+1} = 1$)
        "#3cb44b",  # Green ($q_{j+1} = 0$)
        "#008080",  # Teal ($q_{j+1} = -1$)
        "#000080",  # Navy ($q_{j+1} = -2$)
    ]
    return ListedColormap(colors)


def plot_quotient_regions_no_overlap(D, T, q, quadrants):
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
        quadrant_mask = D >= 2 ** (D_BITS - 2)
    elif quadrants == "quadrants_2_3":
        quadrant_mask = D < -(2 ** (D_BITS - 2))
    else:
        quadrant_mask = True

    q_masked = q[quadrant_mask]
    D_masked = D[quadrant_mask]
    T_masked = T[quadrant_mask]

    for q_val in sorted(color_mapping.keys(), reverse=True):
        mask = q_masked == q_val
        ax.scatter(
            D_masked[mask],
            T_masked[mask],
            c=color_mapping[q_val],
            label=f"$q_{{j+1}} = {q_val}$",
            marker="s",
            s=20,
            alpha=0.7,
            edgecolors="none",
            linewidths=0,
        )

    # Set equal aspect ratio
    ax.set_aspect("equal")

    ax.set_xlim(D_RANGE[0] - 1 / 3, D_RANGE[-1] + 1 / 3)
    ax.xaxis.set_major_locator(
        FixedLocator(np.arange(-(2 ** (D_BITS - 1)), 2 ** (D_BITS - 1), 2 ** (D_FRACTIONAL_BITS - 1)))
    )
    ax.yaxis.set_major_locator(
        FixedLocator(np.arange(-(2 ** (T_BITS - 1)), 2 ** (T_BITS - 1), 2 ** (T_FRACTIONAL_BITS - 1)))
    )

    ax.grid(True)
    ax.set_xlabel(r"$\delta$")
    ax.set_ylabel(r"$\tau_j$")
    ax.set_title(r"$q_{j+1}$")
    if quadrants == "quadrants_1_4":
        ax.legend(loc="upper left")
    elif quadrants == "quadrants_2_3":
        ax.legend(loc="upper right")
    else:
        ax.legend(loc="upper center")
    ax.axhline(y=0, color="k", linestyle="--", alpha=0.3)

    ax.xaxis.set_major_formatter(FuncFormatter(binary_formatter(D_BITS, D_FRACTIONAL_BITS)))
    ax.yaxis.set_major_formatter(FuncFormatter(binary_formatter(T_BITS, T_FRACTIONAL_BITS)))

    plt.tight_layout()
    current_file_path = os.path.abspath(__file__)
    current_dir = os.path.dirname(current_file_path)
    save_path = os.path.abspath(os.path.join(current_dir, "../../figures/division"))
    os.makedirs(save_path, exist_ok=True)
    filename = f"radix4_qds_optimized_{quadrants}.pdf"
    full_save_path = os.path.join(save_path, filename)
    plt.savefig(full_save_path, dpi=600, format="pdf", bbox_inches="tight")
    plt.show()


def main():
    try:
        # Calculate quotient digits without overlaps
        q_no_overlap = get_quotient_digit_no_overlap(D, T, QUOTIENT_CONDITIONS)

        # Plot the results for quadrants 1, 2, 3, and 4
        plot_quotient_regions_no_overlap(D, T, q_no_overlap, "quadrants_1_2_3_4")

    except Exception as e:
        print(f"An error occurred: {e}")


if __name__ == "__main__":
    main()
