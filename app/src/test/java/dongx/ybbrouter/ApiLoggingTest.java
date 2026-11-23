package dongx.ybbrouter;
import org.junit.Test;
import static org.junit.Assert.*;

public class ApiLoggingTest {
    @Test public void logsOmitQueryValuesAndResourceIdentifiers() {
        assertEquals("game/list",ApiClient.logRoute("game/list?type=ps5&token=secret"));
        assertEquals("router/unbind/:id",ApiClient.logRoute("router/unbind/private-router-id"));
        assertEquals("client/pop/:id",ApiClient.logRoute("client/pop/private-offer-id"));
        assertEquals("router/message",ApiClient.logRoute("router/message"));
    }
}
