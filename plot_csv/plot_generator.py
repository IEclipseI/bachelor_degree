import matplotlib.pyplot as plt
from itertools import groupby
from os import listdir
from pandas import read_csv

x = []
y = []

# slurm = "slurm-2639390"
slurm = "slurm-2609367"
files = [f for f in listdir(".") if f.endswith("csv") and f.startswith(slurm)]
files = sorted(files, key=lambda x: int(x.split("_")[1]))

ranges = set([f.split("_")[1] for f in files])
d = {}
res = [list(i) for j, i in groupby(files,
                                   lambda a: a.split('_')[1])]
print(res)

markers = ['.', 'o', 'v', '^', '<', '>', '1', '2', '3', '4', '8', 's', 'p', '*', 'h', 'H', '+', 'x', 'D', 'd', '|', '_',
           'P', 'X', 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 'None', None, ' ', '']

for files_block in res:
    plots_on_figure = len(files_block)
    print(files_block)
    sorted_by_ratio = sorted(files_block, key=lambda x: float(x.split("_")[3][:-4]))
    size = 4
    # fig, axs = plt.subplots(2, 3, figsize=(size * 3, size * 2))
    fig = plt.figure(figsize=(size * plots_on_figure, size ))
    fig.subplots_adjust(bottom=0.25)
    fig.subplots_adjust(left=0.05)
    fig.subplots_adjust(right=0.95)

    for i in range(plots_on_figure):
        f = sorted_by_ratio[i]
        print(sorted_by_ratio)
        marker_ind = 0
        ratio = f.split('_')[3][:-4]
        df = read_csv(f)
        cores = list(df.index)
        header = list(df)
        axs = fig.add_subplot(1, plots_on_figure, i + 1)
        for col in range(len(header)):
            axs.plot(cores, df.iloc[:, col], label=header[col], marker=markers[marker_ind])
            axs.set_title("Update rate " + ratio)
            plt.xlabel("Number of threads")
            plt.ylabel("Throughput, operations/second")
            # plt.sharey(axs[i // 3, i % 3])
            # axs[i].legend(loc='lower right', prop={'size': 8})
            marker_ind = marker_ind + 1
        # if i == plots_on_figure - 1:

    # plt.xlabel('x')
    # plt.ylabel('y')
    handles, labels = axs.get_legend_handles_labels()
    fig.legend(handles, labels, loc='upper center',
               bbox_to_anchor=(0.5,0.15), fancybox=False, shadow=False, ncol=2)
    # fig.legend(handles, labels, loc='best', prop={'size': 12})
    values_range = f.split('_')[1]
    fig.suptitle('{0:,}'.format(int(values_range)) + " values")
    handles, labels = axs.get_legend_handles_labels()
    fig.savefig("../plots/" + slurm + "_" + files_block[0].split("_")[1] + "_values.jpeg", dpi=400)
