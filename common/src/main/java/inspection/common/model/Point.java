package inspection.common.model;

import java.util.Objects;

public class Point {
    
    /** 横坐标（列号），从左到右递增 */
    public int x;
    
    /** 纵坐标（行号），从上到下递增 */
    public int y;

    /** 默认构造函数（供 JSON 反序列化使用） */
    public Point() {}

    /**
     * 构造函数
     * @param x 横坐标
     * @param y 纵坐标
     */
    public Point(int x, int y) {
        this.x = x;
        this.y = y;
    }

    /** 获取横坐标 */
    public int getX() { return x; }
    
    /** 获取纵坐标 */
    public int getY() { return y; }
    
    /** 设置横坐标 */
    public void setX(int x) { this.x = x; }
    
    /** 设置纵坐标 */
    public void setY(int y) { this.y = y; }

    public int distanceTo(Point other) {
        return Math.abs(this.x - other.x) + Math.abs(this.y - other.y);
    }

    /**
     * 判断两个坐标点是否相等
     * 
     * <p>当且仅当 x 和 y 都相等时返回 true
     * 
     * @param o 比较对象
     * @return 是否相等
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Point)) return false;
        Point p = (Point) o;
        return x == p.x && y == p.y;
    }

    /**
     * 计算哈希码
     * 
     * <p>基于 x 和 y 计算，确保相等的 Point 具有相同的哈希码
     * 支持在 HashMap、HashSet 等集合中正确使用
     * 
     * @return 哈希码值
     */
    @Override
    public int hashCode() {
        return Objects.hash(x, y);
    }

    /**
     * 转换为字符串表示
     * 
     * <p>格式："(x,y)"，便于日志输出和调试
     * 
     * @return 字符串形式的坐标
     */
    @Override
    public String toString() {
        return "(" + x + "," + y + ")";
    }
}