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


def detect_overlaps(D, T):
    overlaps = {
        "q2_q1": np.zeros_like(D, dtype=bool),
        "q1_q0": np.zeros_like(D, dtype=bool),
        "q0_qm1": np.zeros_like(D, dtype=bool),
        "qm1_qm2": np.zeros_like(D, dtype=bool),
    }

    for case_name, case in QUOTIENT_CONDITIONS.items():
        d_mask = np.isin(D, case["D_values"])
        q_conditions = case["q_conditions"]

        q2_mask = d_mask & q_conditions[0][1](D, T)
        q1_mask = d_mask & q_conditions[1][1](D, T)
        q0_mask = d_mask & q_conditions[2][1](D, T)
        qm1_mask = d_mask & q_conditions[3][1](D, T)
        qm2_mask = d_mask & q_conditions[4][1](D, T)

        overlaps["q2_q1"] |= q2_mask & q1_mask
        overlaps["q1_q0"] |= q1_mask & q0_mask
        overlaps["q0_qm1"] |= q0_mask & qm1_mask
        overlaps["qm1_qm2"] |= qm1_mask & qm2_mask

    return overlaps["q2_q1"], overlaps["q1_q0"], overlaps["q0_qm1"], overlaps["qm1_qm2"]


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
        "#e6194b",  # Red ($q_{j+1} = 2$)
        "#f58231",  # Orange ($q_{j+1} \in \{1,2\}$)
        "#ffe119",  # Yellow ($q_{j+1} = 1$)
        "#bcf60c",  # Yellow-Green ($q_{j+1} \in \{0,1\}$)
        "#3cb44b",  # Green ($q_{j+1} = 0$)
        "#46f0f0",  # Cyan-Green ($q_{j+1} \in \{-1,0\}$)
        "#008080",  # Teal ($q_{j+1} = -1$)
        "#4363d8",  # Blue-Cyan ($q_{j+1} \in \{-2,-1\}$)
        "#000080",  # Navy ($q_{j+1} = -2$)
    ]
    return ListedColormap(colors)


def plot_scatter(
    ax,
    D,
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
        D[mask],
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
            D[mask],
            T[mask],
            c=overlay["color"],
            marker=overlay["marker"],
            s=overlay["s"],
            alpha=overlay.get("alpha", 1.0),
        )


def plot_quotient_regions(D, T, q, overlap_q2_q1, overlap_q1_q0, overlap_q0_qm1, overlap_qm1_qm2, quadrants):
    fig, ax = plt.subplots(figsize=(4, 100))

    cmap = create_custom_colormap()

    color_mapping = {
        2: cmap.colors[0],  # Red
        1: cmap.colors[2],  # Yellow
        0: cmap.colors[4],  # Green
        -1: cmap.colors[6],  # Teal
        -2: cmap.colors[8],  # Navy
        "q2_q1": cmap.colors[1],  # Orange
        "q1_q0": cmap.colors[3],  # Yellow-Green
        "q0_qm1": cmap.colors[5],  # Cyan-Green
        "qm1_qm2": cmap.colors[7],  # Blue-Cyan
    }

    scatter_params = {
        "s": 20,
        "marker": "s",
        "alpha": 0.7,
        "edgecolors": "none",
        "linewidths": 0,
    }
    overlay_params = {"color": "black", "marker": "x", "s": 15, "alpha": 0.9}

    if quadrants == "quadrants_1_4":
        quadrant_mask = D >= 2 ** (D_BITS - 2)
    elif quadrants == "quadrants_2_3":
        quadrant_mask = D < -(2 ** (D_BITS - 2))
    else:
        quadrant_mask = True

    q_masked = q[quadrant_mask]
    overlap_q2_q1_masked = overlap_q2_q1[quadrant_mask]
    overlap_q1_q0_masked = overlap_q1_q0[quadrant_mask]
    overlap_q0_qm1_masked = overlap_q0_qm1[quadrant_mask]
    overlap_qm1_qm2_masked = overlap_qm1_qm2[quadrant_mask]
    D_masked = D[quadrant_mask]
    T_masked = T[quadrant_mask]

    plot_scatter(ax, D_masked, T_masked, q_masked == 2, color_mapping[2], "$q_{j+1} = 2$", **scatter_params)
    plot_scatter(ax, D_masked, T_masked, q_masked == 1, color_mapping[1], "$q_{j+1} = 1$", **scatter_params)
    plot_scatter(ax, D_masked, T_masked, q_masked == 0, color_mapping[0], "$q_{j+1} = 0$", **scatter_params)
    plot_scatter(ax, D_masked, T_masked, q_masked == -1, color_mapping[-1], "$q_{j+1} = -1$", **scatter_params)
    plot_scatter(ax, D_masked, T_masked, q_masked == -2, color_mapping[-2], "$q_{j+1} = -2$", **scatter_params)

    plot_scatter(
        ax,
        D_masked,
        T_masked,
        overlap_q2_q1_masked,
        color_mapping["q2_q1"],
        "$q_{j+1} \in \{1,2\}$",
        **scatter_params,
        overlay=overlay_params,
    )
    plot_scatter(
        ax,
        D_masked,
        T_masked,
        overlap_q1_q0_masked,
        color_mapping["q1_q0"],
        "$q_{j+1} \in \{0,1\}$",
        **scatter_params,
        overlay=overlay_params,
    )
    plot_scatter(
        ax,
        D_masked,
        T_masked,
        overlap_q0_qm1_masked,
        color_mapping["q0_qm1"],
        "$q_{j+1} \in \{-1,0\}$",
        **scatter_params,
        overlay=overlay_params,
    )
    plot_scatter(
        ax,
        D_masked,
        T_masked,
        overlap_qm1_qm2_masked,
        color_mapping["qm1_qm2"],
        "$q_{j+1} \in \{-2,-1\}$",
        **scatter_params,
        overlay=overlay_params,
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
    filename = f"radix4_qds_basic_{quadrants}.pdf"
    full_save_path = os.path.join(save_path, filename)
    plt.savefig(full_save_path, dpi=600, format="pdf", bbox_inches="tight")
    plt.show()


def main():
    try:
        # Calculate quotient digits
        q = get_quotient_digit(D, T)

        # Detect overlap regions
        overlap_q2_q1, overlap_q1_q0, overlap_q0_qm1, overlap_qm1_qm2 = detect_overlaps(D, T)

        # Plot the results for quadrants 1, 2, 3, and 4
        plot_quotient_regions(
            D,
            T,
            q,
            overlap_q2_q1,
            overlap_q1_q0,
            overlap_q0_qm1,
            overlap_qm1_qm2,
            "quadrants_1_2_3_4",
        )

    except Exception as e:
        print(f"An error occurred: {e}")


if __name__ == "__main__":
    main()
