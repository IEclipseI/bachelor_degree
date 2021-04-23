import matplotlib.pyplot as plt
from itertools import groupby
from os import listdir
from pandas import read_csv

x = []
y = []

slurm = "slurm-2609367"

files = [f for f in listdir(".") if f.endswith("csv") and f.startswith(slurm)]
files = sorted(files, key=lambda x: int(x.split("_")[1]))

ranges = set([f.split("_")[1] for f in files])
d = {}
res = [list(i) for j, i in groupby(files,
                                   lambda a: a.split('_')[1])]
print(res)

markers = ['.', 'o', 'v', '^', '<', '>', '1', '2', '3', '4', '8', 's', 'p', '*', 'h', 'H', '+', 'x', 'D', 'd', '|', '_', 'P', 'X', 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 'None', None, ' ', '']

for files_block in res:
    plots_on_figure = len(files_block)
    print(files_block)
    sorted_by_ratio = sorted(files_block, key=lambda x: float(x.split("_")[3][:-4]))
    size = 4
    fig, axs = plt.subplots(1, plots_on_figure, figsize=(size * plots_on_figure, size))
    for i in range(plots_on_figure):
        f = sorted_by_ratio[i]
        print(sorted_by_ratio)
        marker_ind = 0
        ratio = f.split('_')[3][:-4]
        df = read_csv(f)
        cores = list(df.index)
        header = list(df)
        for col in range(len(header)):
            axs[i].plot(cores, df.iloc[:, col], label=header[col], marker=markers[marker_ind])
            axs[i].set_title("Update rate " + ratio)
            axs[i].sharey(axs[0])
            axs[i].legend(loc='lower right', prop={'size': 8})
            marker_ind = marker_ind + 1
        # if i == plots_on_figure - 1:

    # plt.xlabel('x')
    # plt.ylabel('y')
    values_range = f.split('_')[1]
    fig.suptitle(values_range + " values")
    handles, labels = axs[-1].get_legend_handles_labels()
    fig.savefig("../plots/" + slurm + "_" + files_block[0].split("_")[1] + "_values.jpeg", dpi=400)
