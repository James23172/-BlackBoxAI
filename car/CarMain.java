package inspection.car;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CarMain {
    private static final Logger log = LoggerFactory.getLogger(CarMain.class);

    public static void main(String[] args) {
        if (args.length == 0) {
            log.error("Usage: CarMain <carId>");
            log.error("Example: CarMain Car001");
            System.exit(1);
        }

        String carId = args[0];
        log.info("=== Starting Car Knowledge Source: {} ===", carId);

        CarKnowledgeSource car = new CarKnowledgeSource(carId);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down {}...", carId);
            car.shutdown();
        }));

        car.start();
    }
}
