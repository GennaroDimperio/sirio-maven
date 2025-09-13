import pandas as pd
import matplotlib.pyplot as plt
from pathlib import Path
import numpy as np
import re

BASE = Path(".")       # cartella corrente
OUT = BASE / "plots_fase4"
OUT.mkdir(parents=True, exist_ok=True)


def mode_from_name(path: Path) -> str:
    stem = path.stem.lower()
    for key in ["default", "custom", "nofuture"]:
        if key in stem:
            return key
    return path.stem


def load_sli() -> pd.DataFrame:
    files = list(BASE.glob("timeseries_sli_*.csv"))
    if not files:
        print("[info] Nessun timeseries_sli_*.csv trovato, salto i plot SLI.")
        return pd.DataFrame()
    rows = []
    for f in files:
        try:
            df = pd.read_csv(f)
            if df.empty:
                continue
            df["mode"] = mode_from_name(f)
            rows.append(df.iloc[0])
        except Exception as e:
            print(f"[warn] impossibile leggere {f}: {e}")
    return pd.DataFrame(rows)

def load_intervals() -> dict:
    files = list(BASE.glob("timeseries_intervals_*.csv"))
    data = {}
    if not files:
        print("[info] Nessun timeseries_intervals_*.csv trovato, salto i plot per intervallo.")
        return data
    for f in files:
        try:
            df = pd.read_csv(f)
            if df.empty:
                continue
            if {"t_start","t_end"}.issubset(df.columns):
                df["t_mid"] = (df["t_start"] + df["t_end"]) / 2.0
            data[mode_from_name(f)] = df
        except Exception as e:
            print(f"[warn] impossibile leggere {f}: {e}")
    return data

# plots (SLI & intervals)

def plot_sli(sli: pd.DataFrame):
    if sli.empty:
        return
    order = ["default", "custom", "nofuture"]
    sli["order"] = sli["mode"].apply(lambda m: order.index(m) if m in order else len(order))
    sli = sli.sort_values("order")

    # Rejection rate
    fig1 = plt.figure(figsize=(8,4.5))
    plt.bar(sli["mode"], sli["rejection_rate"])
    plt.ylabel("Rejection rate")
    plt.title("Rejection rate per modalità")
    fig1.savefig(OUT / "sli_rejection_rate.png", dpi=150)
    plt.close(fig1)

    # Idle mean
    fig2 = plt.figure(figsize=(8,4.5))
    plt.bar(sli["mode"], sli["idle_mean"])
    plt.ylabel("Idle mean (Pool)")
    plt.title("Idle medio per modalità")
    fig2.savefig(OUT / "sli_idle_mean.png", dpi=150)
    plt.close(fig2)

def plot_intervals(data: dict):
    if not data:
        return
    series_to_plot = [
        ("pool_now", "Pool over time", "pool_now", "intervals_pool.png"),
        ("target_tot", "Target replicas over time", "target_tot", "intervals_target.png"),
        ("rejection_rate", "Rejection rate per interval", "rejection_rate", "intervals_rej.png"),
        ("idle_mean_interval", "Idle mean per interval", "idle_mean_interval", "intervals_idle.png"),
    ]
    for col, title, ylab, fname in series_to_plot:
        fig = plt.figure(figsize=(9,5))
        plotted_any = False
        for mode, df in data.items():
            if col not in df.columns or "t_mid" not in df.columns:
                continue
            plt.plot(df["t_mid"], df[col], label=mode)
            plotted_any = True
        if not plotted_any:
            plt.close(fig)
            continue
        plt.xlabel("time (s)")
        plt.ylabel(ylab)
        plt.title(title)
        plt.legend()
        fig.savefig(OUT / fname, dpi=150)
        plt.close(fig)


def parse_debug(path: Path):
    if not path.exists():
        print("[info] timeseries_debug.csv non trovato, salto i plot di debug.")
        return pd.DataFrame(), pd.DataFrame()

    rows = []
    try:
        with open(path, "r", encoding="utf-8") as f:
            _ = f.readline()  # header "time|event"
            for line in f:
                if "|" not in line:
                    continue
                t, evt = line.strip().split("|", 1)
                if not t:
                    continue
                try:
                    rows.append({"t": float(t), "event": evt})
                except ValueError:
                    continue
    except Exception as e:
        print(f"[warn] errore leggendo {path}: {e}")
        return pd.DataFrame(), pd.DataFrame()

    df = pd.DataFrame(rows)
    if df.empty:
        return pd.DataFrame(), pd.DataFrame()

    # snapshot agli arrivi
    snap = []
    pat = re.compile(r"Pool=(\d+),\s*Ph1=(\d+),\s*Ph2=(\d+),\s*Ph3=(\d+),\s*Ph4=(\d+)")
    df_arr = df[df["event"].str.contains("arrivo", na=False)]
    for _, row in df_arr.iterrows():
        m = pat.search(row["event"])
        if m:
            snap.append({
                "t": row["t"],
                "Pool": int(m.group(1)),
                "Ph1": int(m.group(2)),
                "Ph2": int(m.group(3)),
                "Ph3": int(m.group(4)),
                "Ph4": int(m.group(5)),
            })
    df_snap = pd.DataFrame(snap).sort_values("t")

    # movimenti interni
    moves = []
    df_mov = df[df["event"].str.contains("movimento", na=False)]
    for _, row in df_mov.iterrows():
        txt = row["event"]
        t = row["t"]
        m = re.search(r"da (Ph\d) a (Ph\d)", txt)
        if m:
            moves.append({"t": t, "from": m.group(1), "to": m.group(2)})
        m2 = re.search(r"da (Ph4) a (Pool)", txt)
        if m2:
            moves.append({"t": t, "from": "Ph4", "to": "Pool"})
    df_moves = pd.DataFrame(moves).sort_values("t")
    return df_snap, df_moves

# debug plots (heatmap + timeline)

def plot_debug_state_heatmap(df_snap: pd.DataFrame, out_path: Path):
    """Heatmap: colonne = arrivi, righe = fasi, colore = token count."""
    if df_snap.empty:
        return
    phases = ["Ph1","Ph2","Ph3","Ph4"]
    M = df_snap[phases].to_numpy().T  # 4 x N
    times = df_snap["t"].to_numpy()

    fig = plt.figure(figsize=(10,3.8))
    im = plt.imshow(M, aspect="auto", interpolation="nearest", origin="lower")
    plt.colorbar(im, fraction=0.046, pad=0.04, label="token count")
    plt.yticks(range(len(phases)), phases)
    plt.xticks(range(len(times)), [f"{t:.1f}" for t in times], rotation=45, ha="right")
    plt.title("Heatmap fasi ai primi 20 arrivi (token count)")
    plt.xlabel("arrival time (s)")
    plt.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)

def plot_debug_moves_timeline(df_moves: pd.DataFrame, out_path: Path):
    if df_moves.empty:
        return
    order = [("Ph1","Ph2"), ("Ph2","Ph3"), ("Ph3","Ph4"), ("Ph4","Pool")]
    labels = ["Ph1→Ph2","Ph2→Ph3","Ph3→Ph4","Ph4→Pool"]
    y_positions = {pair: i for i, pair in enumerate(order)}

    fig = plt.figure(figsize=(9,5))
    y_ticks = []
    y_ticklabels = []

    for pair, lab in zip(order, labels):
        sel = df_moves[(df_moves["from"] == pair[0]) & (df_moves["to"] == pair[1])]
        y = np.full(len(sel), y_positions[pair], dtype=float)
        if len(sel) > 0:
            plt.scatter(sel["t"].to_numpy(), y, marker="o", s=30)
        y_ticks.append(y_positions[pair])
        y_ticklabels.append(f"{lab}  (n={len(sel)})")

    plt.yticks(y_ticks, y_ticklabels)
    plt.xlabel("time (s)")
    plt.ylim(-0.5, len(order)-0.5)
    plt.title("Movimenti interni (event timeline, 20 arrivi)")
    plt.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)

if __name__ == "__main__":
    # SLI / intervals
    sli = load_sli()
    data = load_intervals()
    plot_sli(sli)
    plot_intervals(data)

    # Debug
    df_snap, df_moves = parse_debug(BASE / "timeseries_debug.csv")
    if not df_snap.empty:
        plot_debug_state_heatmap(df_snap, OUT / "debug_state_heatmap.png")
    if not df_moves.empty:
        plot_debug_moves_timeline(df_moves, OUT / "debug_moves_timeline.png")

    print("Plot salvati in:", OUT.resolve())
