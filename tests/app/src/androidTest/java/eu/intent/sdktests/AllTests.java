package eu.intent.sdktests;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses({
        ITActivityTest.class,
        ITAddressTest.class,
        ITClassifiedAdTest.class,
        ITContactTest.class,
        ITConversationTest.class,
        ITDataTest.class,
        ITDeviceTypeTest.class,
        ITEquipmentTest.class,
        ITGenericApiTest.class,
        ITGreenGestureTest.class,
        ITLocationTest.class,
        ITMessageTest.class,
        ITNewsTest.class,
        ITOperationTest.class,
        ITPartTest.class,
        ITSiteTest.class,
        ITStateParamsThresholdsTest.class,
        ITStateTemplateTest.class,
        ITStateTest.class,
        ITStreamTest.class,
        ITSubscriptionTest.class,
        ITTagTest.class,
        ITTicketTest.class,
        ITUserTest.class
})
public class AllTests {
}
