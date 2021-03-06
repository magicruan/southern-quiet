package test.idgenerator;

import com.ai.southernquiet.idgenerator.IdGeneratorWorkerTable;
import com.ai.southernquiet.idgenerator.JdbcIdGeneratorAutoConfiguration;
import com.ai.southernquiet.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@ImportAutoConfiguration(JdbcIdGeneratorAutoConfiguration.class)
@RestController
public class JdbcIdGeneratorAppTest {
    private final static Logger log = LoggerFactory.getLogger(JdbcIdGeneratorAppTest.class);

    public static void main(String[] args) {
        SpringApplication.run(JdbcIdGeneratorAppTest.class, args);
    }

    @Autowired
    private IdGenerator idGenerator;

    @Autowired
    private IdGeneratorWorkerTable.Cleaner workerTableCleaner;

    @RequestMapping("getId")
    public long getId() {
        return idGenerator.generate();
    }

    @Scheduled(fixedRate = 1000 * 3600)
    public void cleanConsiderDownedWorker() {
        workerTableCleaner.clearConsiderDowned();
    }
}
