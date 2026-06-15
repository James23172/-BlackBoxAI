package inspection.car;

import inspection.common.client.BlackboardClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Illuminator {
    private static final Logger log = LoggerFactory.getLogger(Illuminator.class);

    private final BlackboardClient bb;

    public Illuminator(BlackboardClient bb) {
        this.bb = bb;
    }

    public void illuminate(int x, int y) {
        bb.illuminateArea(x, y);
        log.debug("3x3 照明 ({},{})", x, y);
    }
}
