import os
import numpy as np
import matplotlib.pyplot as plt
from matplotlib.ticker import FixedLocator, FuncFormatter
from matplotlib.colors import ListedColormap

# Configuration constants
S_BITS = 3
T_BITS = 6
S_FRACTIONAL_BITS = 2
T_FRACTIONAL_BITS = 2
S_RANGE = np.arange(0, 2 ** (S_BITS - 1) + 1)
T_RANGE = np.arange(-(2 ** (T_BITS - 1)), 2 ** (T_BITS - 1))

# Create a grid of integer S and T values
S, T = np.meshgrid(S_RANGE, T_RANGE)

# Define root digit conditions
ROOT_CONDITIONS = {
    "S": {
        "S_values": list(range(int(S_RANGE[-2] + 1) // 2, int(S_RANGE[-2] + 1))) + [2 ** (S_BITS - 1)],
        "s_conditions": [
            (1, lambda s, t: (s < 2 ** (S_BITS - 1)) & (0 <= t) & (t < 4 * (s + 1))),
            (
                0,
                lambda s, t: ((s < 2 ** (S_BITS - 1)) & (-2 * (s - 1) <= t) & (t <= 2 * s - 2))
                | ((s == 2 ** (S_BITS - 1)) & (-2 * (s - 1) <= t) & (t <= -1)),
            ),
            (
                -1,
                lambda s, t: ((s < 2 ** (S_BITS - 1)) & (-4 * (s + 1) - 2 < t) & (t <= -2))
                | ((s == 2 ** (S_BITS - 1)) & (-4 * s - 2 < t) & (t <= -2)),
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


def detect_overlaps(S, T):
    overlaps = {
        "s1_s0": np.zeros_like(S, dtype=bool),
        "s0_sm1": np.zeros_like(S, dtype=bool),
    }

    for case_name, case in ROOT_CONDITIONS.items():
        s_mask = np.isin(S, case["S_values"])
        s_conditions = case["s_conditions"]

        s1_mask = s_mask & s_conditions[0][1](S, T)
        s0_mask = s_mask & s_conditions[1][1](S, T)
        sm1_mask = s_mask & s_conditions[2][1](S, T)

        overlaps["s1_s0"] |= s1_mask & s0_mask
        overlaps["s0_sm1"] |= s0_mask & sm1_mask

    return overlaps["s1_s0"], overlaps["s0_sm1"]


def binary_formatter(bits, fractional_bits):
    def formatter(x, pos):
        if not np.isfinite(x):  # Handle NaN or inf
            return "NaN"
        x_int = int(x)
        if x_int < 0:
            x_int = 2**bits + x_int
        binary_string = bin(x_int)[2:].zfill(bits)
        return f"{binary_string[:-fractional_bits]}.{binary_string[-fractional_bits:]}"

    return formatter


def create_custom_colormap():
    colors = [
        "#e6194b",  # Red ($s_{j+1} = 1$)
        "#ffe119",  # Yellow ($s_{j+1} \in \{0,1\}$)
        "#3cb44b",  # Green ($s_{j+1} = 0$)
        "#008080",  # Teal ($s_{j+1} \in \{-1,0\}$)
        "#000080",  # Navy ($s_{j+1} = -1$)
    ]
    return ListedColormap(colors)


def plot_scatter(
    ax,
    S,
    T,
    mask,
    color,
    label,
    marker="s",
    s=20,
    alpha=0.8,
    edgecolors="none",
    linewidths=0,
    overlay=None,
):
    ax.scatter(
        S[mask],
        T[mask],
        c=color,
        label=label,
        marker=marker,
        s=s,
        alpha=alpha,
        edgecolors=edgecolors,
        linewidths=linewidths,
    )
    if overlay:
        ax.scatter(
            S[mask],
            T[mask],
            c=overlay["color"],
            marker=overlay["marker"],
            s=overlay["s"],
            alpha=overlay.get("alpha", 1.0),
        )


def plot_root_regions(S, T, s, overlap_s1_s0, overlap_s0_sm1, quadrants):
    fig, ax = plt.subplots(figsize=(4, 100))

    cmap = create_custom_colormap()

    color_mapping = {
        1: cmap.colors[0],  # Red
        0: cmap.colors[2],  # Green
        -1: cmap.colors[4],  # Navy
        "s1_s0": cmap.colors[1],  # Yellow
        "s0_sm1": cmap.colors[3],  # Teal
    }

    scatter_params = {
        "s": 200,
        "marker": "s",
        "alpha": 0.7,
        "edgecolors": "none",
        "linewidths": 0,
    }
    overlay_params = {"color": "black", "marker": "x", "s": 150, "alpha": 0.9}

    if quadrants == "quadrants_1_4":
        quadrant_mask = S >= 2 ** (S_BITS - 2)
    elif quadrants == "quadrants_2_3":
        quadrant_mask = S < -(2 ** (S_BITS - 2))
    else:
        quadrant_mask = True

    s_masked = s[quadrant_mask]
    overlap_s1_s0_masked = overlap_s1_s0[quadrant_mask]
    overlap_s0_sm1_masked = overlap_s0_sm1[quadrant_mask]
    S_masked = S[quadrant_mask]
    T_masked = T[quadrant_mask]

    plot_scatter(ax, S_masked, T_masked, s_masked == 1, color_mapping[1], "$s_{j+1} = 1$", **scatter_params)
    plot_scatter(ax, S_masked, T_masked, s_masked == 0, color_mapping[0], "$s_{j+1} = 0$", **scatter_params)
    plot_scatter(ax, S_masked, T_masked, s_masked == -1, color_mapping[-1], "$s_{j+1} = -1$", **scatter_params)

    plot_scatter(
        ax,
        S_masked,
        T_masked,
        overlap_s1_s0_masked,
        color_mapping["s1_s0"],
        "$s_{j+1} \in \{0,1\}$",
        **scatter_params,
        overlay=overlay_params,
    )
    plot_scatter(
        ax,
        S_masked,
        T_masked,
        overlap_s0_sm1_masked,
        color_mapping["s0_sm1"],
        "$s_{j+1} \in \{-1,0\}$",
        **scatter_params,
        overlay=overlay_params,
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
    filename = f"radix2_rds_basic_{quadrants}.pdf"
    full_save_path = os.path.join(save_path, filename)
    plt.savefig(full_save_path, dpi=600, format="pdf", bbox_inches="tight")
    plt.show()


def main():
    try:
        # Calculate root digits
        s = get_root_digit(S, T)

        # Detect overlap regions
        overlap_s1_s0, overlap_s0_sm1 = detect_overlaps(S, T)

        # Plot the results for quadrants 1 and 4
        plot_root_regions(S, T, s, overlap_s1_s0, overlap_s0_sm1, "quadrants_1_4")

    except Exception as e:
        print(f"An error occurred: {e}")


if __name__ == "__main__":
    main()
