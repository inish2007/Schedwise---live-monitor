package dev.schedwise;
import dev.schedwise.api.GameShieldApi;
import dev.schedwise.gameshield.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class GameShieldApiTest {
 @Test void errorsCarryMachineCodeAndRecoveryState(){
  var controller=mock(ShieldController.class);when(controller.getState()).thenReturn(ShieldState.idle());var api=new GameShieldApi(controller);
  var invalid=api.error(new IllegalArgumentException("Selection changed"));assertEquals(400,invalid.getStatusCode().value());assertEquals("STALE_SELECTION",invalid.getBody().code());assertEquals("OFF",invalid.getBody().recovery().status());
  var unavailable=api.error(new IllegalStateException("Guardian unavailable"));assertEquals(409,unavailable.getStatusCode().value());assertEquals("GUARDIAN_UNAVAILABLE",unavailable.getBody().code());assertTrue(unavailable.getBody().retryable());
 }
}
