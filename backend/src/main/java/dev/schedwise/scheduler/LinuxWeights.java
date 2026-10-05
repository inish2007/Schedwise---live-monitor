package dev.schedwise.scheduler;

/**
 * Standard Linux kernel nice-to-weight mapping from kernel/sched/core.c (sched_prio_to_weight).
 * Each nice step corresponds to roughly an inverse 1.25 multiplier (~10% difference in CPU share).
 * Nice 0 corresponds to NICE_0_LOAD = 1024.
 */
public final class LinuxWeights {
    private LinuxWeights() {}

    public static final int NICE_0_LOAD = 1024;

    private static final int[] SCHED_PRIO_TO_WEIGHT = {
        /* -20 */ 88761, 71755, 56483, 46273, 36291,
        /* -15 */ 29154, 23254, 18705, 14949, 11916,
        /* -10 */  9548,  7620,  6100,  4904,  3906,
        /*  -5 */  3121,  2501,  1991,  1586,  1277,
        /*   0 */  1024,   820,   655,   526,   423,
        /*   5 */   335,   272,   215,   172,   137,
        /*  10 */   110,    87,    70,    56,    45,
        /*  15 */    36,    29,    23,    18,    15
    };

    /**
     * Map a nice value in [-20, 19] to its Linux fair-scheduler CFS weight.
     * Values outside [-20, 19] are clamped to the valid range.
     */
    public static int niceToWeight(int nice) {
        int clamped = Math.max(-20, Math.min(19, nice));
        return SCHED_PRIO_TO_WEIGHT[clamped + 20];
    }
}
