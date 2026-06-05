package inspection.navigator;

import inspection.common.model.Point;
import java.util.*;

/**
 * A* 路径规划器
 * 启发函数：曼哈顿距离
 */
public class AStarPlanner implements PathPlanner {

    private static final int[][] DIRECTIONS = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};

    private static class Node implements Comparable<Node> {
        Point point;
        int g; // 从起点到当前节点的实际代价（步数）
        int h; // 启发式估计代价（到终点的曼哈顿距离）
        Node parent;

        Node(Point point, int g, int h, Node parent) {
            this.point = point;
            this.g = g;
            this.h = h;
            this.parent = parent;
        }

        int f() { return g + h; }

        @Override
        public int compareTo(Node other) {
            return Integer.compare(this.f(), other.f());
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Node)) return false;
            Node other = (Node) obj;
            return point.equals(other.point);
        }

        @Override
        public int hashCode() {
            return point.hashCode();
        }
    }

    @Override
    public List<Point> plan(Point start, Point target, boolean[][] obstacles, int width, int height) {
        if (start.equals(target)) {
            List<Point> path = new ArrayList<>();
            path.add(start);
            return path;
        }

        PriorityQueue<Node> openSet = new PriorityQueue<>();
        Map<Point, Integer> bestG = new HashMap<>(); // 记录到每个点的最佳 g 值

        Node startNode = new Node(start, 0, heuristic(start, target), null);
        openSet.add(startNode);
        bestG.put(start, 0);

        while (!openSet.isEmpty()) {
            Node current = openSet.poll();

            if (current.point.equals(target)) {
                return reconstructPath(current);
            }

            for (int[] dir : DIRECTIONS) {
                int nx = current.point.getX() + dir[0];
                int ny = current.point.getY() + dir[1];
                if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                if (obstacles[ny][nx]) continue;

                Point neighborPoint = new Point(nx, ny);
                int tentativeG = current.g + 1;
                Integer existingG = bestG.get(neighborPoint);
                if (existingG == null || tentativeG < existingG) {
                    int h = heuristic(neighborPoint, target);
                    Node neighborNode = new Node(neighborPoint, tentativeG, h, current);
                    openSet.add(neighborNode);
                    bestG.put(neighborPoint, tentativeG);
                }
            }
        }
        return null; // 无路径
    }

    /**
     * 曼哈顿距离启发函数
     */
    private int heuristic(Point a, Point b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY());
    }

    /**
     * 从目标节点回溯路径
     */
    private List<Point> reconstructPath(Node targetNode) {
        LinkedList<Point> path = new LinkedList<>();
        Node current = targetNode;
        while (current != null) {
            path.addFirst(current.point);
            current = current.parent;
        }
        return path;
    }
}
