package euphy.upo.ceaselesscag.world.pathfinding;

/**
 * Catmull-Rom 样条曲线数学工具。
 */
public final class CatmullRom {


    private CatmullRom() {}

    public static double interpolate(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;

        return 0.5 * ((2 * p1) +
                (-p0 + p2) * t +
                (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 +
                (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }
}