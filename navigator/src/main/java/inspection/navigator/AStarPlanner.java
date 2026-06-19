package inspection.navigator;

import inspection.common.model.Point;
import java.util.*;

public class AStarPlanner implements PathPlanner {
    private static final int[][] DIRS = {{0,1},{1,0},{0,-1},{-1,0}};

    @Override
    public List<Point> plan(Point start, Point target, boolean[][] obstacles, boolean[][] explored, int w, int h) {
        var open = new PriorityQueue<Node>();
        var closed = new boolean[h][w];
        var gScore = new int[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) gScore[y][x] = Integer.MAX_VALUE;
        var cameFrom = new Point[h][w];

        gScore[start.y][start.x] = 0;
        open.add(new Node(start, heuristic(start, target)));

        while (!open.isEmpty()) {
            var cur = open.poll();
            if (cur.p.equals(target)) {
                List<Point> path = new ArrayList<>();
                Point at = target;
                while (at != null) { path.add(at); at = cameFrom[at.y][at.x]; }
                Collections.reverse(path);
                return path;
            }
            if (closed[cur.p.y][cur.p.x]) continue;
            closed[cur.p.y][cur.p.x] = true;

            for (int[] d : DIRS) {
                int nx = cur.p.x + d[0], ny = cur.p.y + d[1];
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                if (obstacles[ny][nx]) continue;
                int ng = gScore[cur.p.y][cur.p.x] + 1;
                if (ng < gScore[ny][nx]) {
                    gScore[ny][nx] = ng;
                    cameFrom[ny][nx] = cur.p;
                    open.add(new Node(new Point(nx, ny), ng + heuristic(new Point(nx, ny), target)));
                }
            }
        }
        return null;
    }

    private int heuristic(Point a, Point b) { return Math.abs(a.x - b.x) + Math.abs(a.y - b.y); }

    static class Node implements Comparable<Node> {
        Point p; int f;
        Node(Point p, int f) { this.p = p; this.f = f; }
        public int compareTo(Node o) { return Integer.compare(this.f, o.f); }
    }
}
