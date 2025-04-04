package com.crt.fujitsu.ride_pricing_app;

import com.crt.fujitsu.ride_pricing_app.service_logic.ApiWeather;
import com.crt.fujitsu.ride_pricing_app.service_logic.PriceMultiplierCalculator;
import com.crt.fujitsu.ride_pricing_app.service_logic.RiskScoreCalculator;
import com.crt.fujitsu.ride_pricing_app.service_logic.PricingService;
import com.crt.fujitsu.ride_pricing_app.dto.RidePriceEstimate;
import com.crt.fujitsu.ride_pricing_app.model.WeatherData;
import com.crt.fujitsu.ride_pricing_app.repository.WeatherDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;


@SpringBootTest
class RidePricingAppApplicationTests {

	@Autowired
	private WeatherDataRepository weatherDataRepository;

	@BeforeEach
	void clear() {
		weatherDataRepository.deleteAll();
	}

	// Normal case: valid city and vehicle ("Car" in "Tallinn") with normal weather data.
	@Test
	void testCalculateRidePrice_TallinnAndCar() {
		// Given
		String city = "Tallinn";
		String vehicle = "Car";
		WeatherData testWeatherData = new WeatherData();
		testWeatherData.setStationName("Tallinn-Harku");
		testWeatherData.setAirTemperature(5.0);
		testWeatherData.setWindSpeed(5.0);
		testWeatherData.setPhenomenon("clear");
		testWeatherData.setObservationTimestamp(LocalDateTime.now());
		weatherDataRepository.save(testWeatherData);

		// When
		PricingService pricingService = new PricingService(weatherDataRepository);
		RidePriceEstimate estimate = pricingService.calculateRidePrice(city, vehicle, LocalDateTime.now());

		// Then
		double expectedFee = 4.0;
		double actualFee = estimate.getFinalPrice();
		assertEquals(expectedFee, actualFee, 0.001, "Fee should match expected");
	}

	@Test
	void testCalculateRidePrice_NoCitySpecified() {
		PricingService pricingService = new PricingService(weatherDataRepository);
		String vehicle = "Car";
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> pricingService.calculateRidePrice(null, vehicle, LocalDateTime.now()));
		assertEquals("No city specified", exception.getMessage());
	}

	@Test
	void testCalculateRidePrice_NoVehicleSpecified() {
		PricingService pricingService = new PricingService(weatherDataRepository);
		String city = "Tallinn";
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> pricingService.calculateRidePrice(city, null, LocalDateTime.now()));
		assertEquals("No vehicle type specified", exception.getMessage());
	}

	@Test
	void testCalculateRidePrice_InvalidVehicle() {
		PricingService pricingService = new PricingService(weatherDataRepository);
		String city = "Tallinn";
		String vehicle = "Submarine";
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> pricingService.calculateRidePrice(city, vehicle, LocalDateTime.now()));
		assertEquals("Invalid vehicle type: " + vehicle, exception.getMessage());
	}

	@Test
	void testCalculateRidePrice_UnknownCity() {
		PricingService pricingService = new PricingService(weatherDataRepository);
		String city = "Tõrva";
		String vehicle = "Car";
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> pricingService.calculateRidePrice(city, vehicle, LocalDateTime.now()));
		assertEquals("Unknown city: " + city, exception.getMessage());
	}

	@Test
	void testCalculateRidePrice_WeatherDataNotAvailable() {
		String city = "Tallinn";
		String vehicle = "Car";
		PricingService pricingService = new PricingService(weatherDataRepository);
		IllegalStateException exception = assertThrows(IllegalStateException.class,
				() -> pricingService.calculateRidePrice(city, vehicle, LocalDateTime.now()));
		assertEquals("No weather data available for city: " + city, exception.getMessage());
	}

	@Test
	void testCalculateRidePrice_RestrictedDueToWeather() {
		// For "Bike": if wind speed is too high, extra fee calculation should throw an exception.
		String city = "Tallinn";
		String vehicle = "Bike";
		WeatherData testWeatherData = new WeatherData();
		testWeatherData.setStationName("Tallinn-Harku");
		testWeatherData.setAirTemperature(5.0);
		testWeatherData.setWindSpeed(20.0001);  // Too high wind speed
		testWeatherData.setPhenomenon("Clear");
		testWeatherData.setObservationTimestamp(LocalDateTime.now());
		weatherDataRepository.save(testWeatherData);
		PricingService pricingService = new PricingService(weatherDataRepository);
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> pricingService.calculateRidePrice(city, vehicle, LocalDateTime.now()));
		assertEquals("Usage of selected vehicle type is forbidden", exception.getMessage());
	}

	@Test
	void testCalculateRidePrice_RestrictedDueToPhenomenon() {
		// For "Scooter": if phenomenon is dangerous (e.g., "Glaze"), should throw exception.
		String city = "Tallinn";
		String vehicle = "Scooter";
		WeatherData testWeatherData = new WeatherData();
		testWeatherData.setStationName("Tallinn-Harku");
		testWeatherData.setAirTemperature(5.0);
		testWeatherData.setWindSpeed(5.0);
		testWeatherData.setPhenomenon("Glaze");
		testWeatherData.setObservationTimestamp(LocalDateTime.now());
		weatherDataRepository.save(testWeatherData);
		PricingService pricingService = new PricingService(weatherDataRepository);
		IllegalStateException exception = assertThrows(IllegalStateException.class,
				() -> pricingService.calculateRidePrice(city, vehicle, LocalDateTime.now()));
		assertEquals("Usage of selected vehicle type is forbidden", exception.getMessage());
	}

	@Test
	void testCalculateRidePrice_ExtraFeeCalculation() {
		// For "Scooter" in Tartu with colder temperature, moderate wind, and "snow" phenomenon.
		String city = "Tartu";
		String vehicle = "Scooter";
		WeatherData testWeatherData = new WeatherData();
		testWeatherData.setStationName("Tartu-Tõravere");
		testWeatherData.setAirTemperature(-5.0);  // Colder temperature triggers extra fee
		testWeatherData.setWindSpeed(15.0);        // Moderate wind speed
		testWeatherData.setPhenomenon("snow");     // Triggers extra fee
		testWeatherData.setObservationTimestamp(LocalDateTime.now());
		weatherDataRepository.save(testWeatherData);
		double baseFee = 3.0;
		double extraFee = 1.5;
		double expectedFee = baseFee + extraFee;
		PricingService pricingService = new PricingService(weatherDataRepository);
		RidePriceEstimate estimate = pricingService.calculateRidePrice(city, vehicle, LocalDateTime.now());
		double actualFee = estimate.getFinalPrice();
		assertEquals(expectedFee, actualFee, 0.001, "The ride price should match the expected value.");
	}

	@Test
	void testCalculateRidePrice_NullCityAndVehicle() {
		PricingService pricingService = new PricingService(weatherDataRepository);
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> pricingService.calculateRidePrice(null, null, LocalDateTime.now()));
		assertEquals("No city specified", exception.getMessage());
	}

	@Test
	void testCalculateRidePrice_EmptyCityAndVehicle() {
		PricingService pricingService = new PricingService(weatherDataRepository);
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> pricingService.calculateRidePrice("", "", LocalDateTime.now()));
		assertEquals("No city specified", exception.getMessage());
	}

	@Test
	void testCalculateRidePrice_MaxWindSpeedBike() {
		// For "Bike" in Tallinn with airTemperature=10.0, windSpeed=20.0 (edge of acceptable)
		String city = "Tallinn";
		String vehicle = "Bike";
		WeatherData testWeatherData = new WeatherData();
		testWeatherData.setStationName("Tallinn-Harku");
		testWeatherData.setAirTemperature(10.0);
		testWeatherData.setWindSpeed(20.0);  // Maximum acceptable wind speed for bike
		testWeatherData.setPhenomenon("clear");
		testWeatherData.setObservationTimestamp(LocalDateTime.now());
		weatherDataRepository.save(testWeatherData);
		double expectedFee = 3.0 + 0.5;  // baseFee (3.0) + extraFee (0.5)
		PricingService pricingService = new PricingService(weatherDataRepository);
		RidePriceEstimate estimate = pricingService.calculateRidePrice(city, vehicle, LocalDateTime.now());
		double actualFee = estimate.getFinalPrice();
		assertEquals(expectedFee, actualFee, 0.001);
	}

	@Test
	void testCalculateRidePrice_MissingPhenomenon() {
		// For "Scooter" in Tallinn with no phenomenon data provided.
		String city = "Tallinn";
		String vehicle = "Scooter";
		WeatherData testWeatherData = new WeatherData();
		testWeatherData.setStationName("Tallinn-Harku");
		testWeatherData.setAirTemperature(0.0);
		testWeatherData.setWindSpeed(5.0);
		testWeatherData.setPhenomenon(null);  // Missing phenomenon
		testWeatherData.setObservationTimestamp(LocalDateTime.now());
		weatherDataRepository.save(testWeatherData);
		double expectedFee = 3.5;
		PricingService pricingService = new PricingService(weatherDataRepository);
		RidePriceEstimate estimate = pricingService.calculateRidePrice(city, vehicle, LocalDateTime.now());
		double actualFee = estimate.getFinalPrice();
		assertEquals(expectedFee, actualFee, 0.001);
	}

	@Test
	void testCalculateRidePrice_EmptyRepository() {
		// When there is no weather data available.
		String city = "Tallinn";
		String vehicle = "Car";
		PricingService pricingService = new PricingService(weatherDataRepository);
		IllegalStateException exception = assertThrows(IllegalStateException.class,
				() -> pricingService.calculateRidePrice(city, vehicle, LocalDateTime.now()));
		assertEquals("No weather data available for city: " + city, exception.getMessage());
	}

	// Tests for Price Multiplier Calculator

	@Nested
	@SpringBootTest
	public class PriceMultiplierCalculatorTests {

		@Test
		void testMorningRushHour() {
			// Test during morning rush (e.g., 8:00 AM)
			LocalDateTime rideTime = LocalDateTime.of(2025, 4, 5, 8, 0);
			double multiplier = PriceMultiplierCalculator.calculatePriceMultiplier(rideTime);
			assertEquals(1.2, multiplier, 0.001, "Morning rush hour (8:00) should have multiplier 1.2");
		}

		@Test
		void testEveningRushHour() {
			// Test during evening rush (e.g., 17:00 or 5:00 PM)
			LocalDateTime rideTime = LocalDateTime.of(2025, 4, 5, 17, 0);
			double multiplier = PriceMultiplierCalculator.calculatePriceMultiplier(rideTime);
			assertEquals(1.2, multiplier, 0.001, "Evening rush hour (17:00) should have multiplier 1.2");
		}

		@Test
		void testEarlyMorning() {
			// Test early morning, before 5:00 AM (e.g., 3:30 AM)
			LocalDateTime rideTime = LocalDateTime.of(2025, 4, 5, 3, 30);
			double multiplier = PriceMultiplierCalculator.calculatePriceMultiplier(rideTime);
			assertEquals(1.1, multiplier, 0.001, "Early morning (3:30) should have multiplier 1.1");
		}

		@Test
		void testNormalTime() {
			// Test a time that doesn't fall under special conditions (e.g., 12:00 PM)
			LocalDateTime rideTime = LocalDateTime.of(2025, 4, 5, 12, 0);
			double multiplier = PriceMultiplierCalculator.calculatePriceMultiplier(rideTime);
			assertEquals(1.0, multiplier, 0.001, "Normal time (12:00) should have multiplier 1.0");
		}

		@Test
		void testEdgeCaseAt6AM() {
			// At exactly 6:00 AM, condition for rush hour is hour > 6, so 6:00 should be normal.
			LocalDateTime rideTime = LocalDateTime.of(2025, 4, 5, 6, 0);
			double multiplier = PriceMultiplierCalculator.calculatePriceMultiplier(rideTime);
			assertEquals(1.0, multiplier, 0.001, "At exactly 6:00 AM, multiplier should be 1.0");
		}

		@Test
		void testEdgeCaseAt10AM() {
			// At exactly 10:00 AM, condition for morning rush is hour < 10, so 10:00 should be normal.
			LocalDateTime rideTime = LocalDateTime.of(2025, 4, 5, 10, 0);
			double multiplier = PriceMultiplierCalculator.calculatePriceMultiplier(rideTime);
			assertEquals(1.0, multiplier, 0.001, "At exactly 10:00 AM, multiplier should be 1.0");
		}

		@Test
		void testEdgeCaseAt3PM() {
			// At exactly 15:00 (3:00 PM), condition for evening rush is hour > 15, so 15:00 should be normal.
			LocalDateTime rideTime = LocalDateTime.of(2025, 4, 5, 15, 0);
			double multiplier = PriceMultiplierCalculator.calculatePriceMultiplier(rideTime);
			assertEquals(1.0, multiplier, 0.001, "At exactly 15:00, multiplier should be 1.0");
		}

		@Test
		void testEdgeCaseAt8PM() {
			// At exactly 20:00 (8:00 PM), condition for evening rush is hour < 20, so 20:00 should be normal.
			LocalDateTime rideTime = LocalDateTime.of(2025, 4, 5, 20, 0);
			double multiplier = PriceMultiplierCalculator.calculatePriceMultiplier(rideTime);
			assertEquals(1.0, multiplier, 0.001, "At exactly 20:00, multiplier should be 1.0");
		}
	}

	// Tests for RiskScoreCalculator

	@Nested
	@SpringBootTest
	class RiskScoreCalculatorTests {

		/**
		 * For a Car with normal conditions:
		 * - windSpeed = 5 (below moderate threshold)
		 * - airTemperature = 20 (within [-10, 35])
		 * - phenomenon = "clear" (no deduction)
		 * Expect score = 100 (no deductions for Car, base deduction is 0)
		 */
		@Test
		void testNormalCarNoDeductions() {
			WeatherData weatherData = new WeatherData();
			weatherData.setWindSpeed(5.0);
			weatherData.setAirTemperature(20.0);
			weatherData.setPhenomenon("clear");

			int score = RiskScoreCalculator.computeRiskScore(weatherData, "Car");
			assertEquals(100, score, "For a Car with normal weather, score should be 100.");
		}

		/**
		 * For a Bike with moderate wind:
		 * - windSpeed = 15 (between 10 and 20: moderate severity)
		 * - airTemperature = 20 (normal)
		 * - phenomenon = "clear" (no deduction)
		 * Deductions for Bike:
		 * Wind deduction (moderate) = 10
		 * Base deduction for Bike = 5
		 * Expected score = 100 - 10 - 5 = 85.
		 */
		@Test
		void testModerateWindBike() {
			WeatherData weatherData = new WeatherData();
			weatherData.setWindSpeed(15.0);
			weatherData.setAirTemperature(20.0);
			weatherData.setPhenomenon("clear");

			int score = RiskScoreCalculator.computeRiskScore(weatherData, "Bike");
			assertEquals(85, score, "For a Bike with moderate wind, score should be 85.");
		}

		/**
		 * For a Scooter with temperature out-of-range:
		 * - airTemperature = -15 (below -10, so deduction applies)
		 * - windSpeed = 5 (normal)
		 * - phenomenon = "clear" (no deduction)
		 * Deductions for Scooter:
		 * Temperature deduction = 25
		 * Base deduction for Scooter = 10
		 * Expected score = 100 - 25 - 10 = 65.
		 */
		@Test
		void testTemperatureOutOfRangeForScooter() {
			WeatherData weatherData = new WeatherData();
			weatherData.setWindSpeed(5.0);
			weatherData.setAirTemperature(-15.0);
			weatherData.setPhenomenon("clear");

			int score = RiskScoreCalculator.computeRiskScore(weatherData, "Scooter");
			assertEquals(65, score, "For a Scooter with low temperature, score should be 65.");
		}

		/**
		 * For a Car with a moderate phenomenon:
		 * - phenomenon = "rain" (moderate severity for Car gives deduction = 5)
		 * - wind and temperature normal.
		 * Expected score = 100 - 5 = 95.
		 */
		@Test
		void testPhenomenonModerateCar() {
			WeatherData weatherData = new WeatherData();
			weatherData.setWindSpeed(5.0);
			weatherData.setAirTemperature(20.0);
			weatherData.setPhenomenon("rain");

			int score = RiskScoreCalculator.computeRiskScore(weatherData, "Car");
			assertEquals(95, score, "For a Car with rain, score should be 95.");
		}

		/**
		 * For a Bike with a severe phenomenon:
		 * - phenomenon = "thunder" (severe: for Bike, deduction = 25)
		 * - wind and temperature normal.
		 * - Base deduction for Bike = 5.
		 * Expected score = 100 - 25 - 5 = 70.
		 */
		@Test
		void testPhenomenonSevereBike() {
			WeatherData weatherData = new WeatherData();
			weatherData.setWindSpeed(5.0);
			weatherData.setAirTemperature(20.0);
			weatherData.setPhenomenon("thunder");

			int score = RiskScoreCalculator.computeRiskScore(weatherData, "Bike");
			assertEquals(70, score, "For a Bike with thunder, score should be 70.");
		}

		/**
		 * For a Scooter with severe wind:
		 * - windSpeed = 22 (severe, deduction for Scooter = 30)
		 * - Other conditions normal.
		 * - Base deduction for Scooter = 10.
		 * Expected score = 100 - 30 - 10 = 60.
		 */
		@Test
		void testSevereWindScooter() {
			WeatherData weatherData = new WeatherData();
			weatherData.setWindSpeed(22.0);
			weatherData.setAirTemperature(20.0);
			weatherData.setPhenomenon("clear");

			int score = RiskScoreCalculator.computeRiskScore(weatherData, "Scooter");
			assertEquals(60, score, "For a Scooter with severe wind, score should be 60.");
		}

		/**
		 * Test clamping: conditions that produce a very low score should be clamped to 0.
		 */
		@Test
		void testClampingScoreLowerBound() {
			WeatherData weatherData = new WeatherData();
			weatherData.setWindSpeed(100.0);    // extreme wind
			weatherData.setAirTemperature(-50.0); // extreme temperature
			weatherData.setPhenomenon("thunder");  // severe phenomenon
			int score = RiskScoreCalculator.computeRiskScore(weatherData, "Scooter");
			assertEquals(0, score, "Score should be clamped to 0 if deductions exceed 100.");
		}

		/**
		 * Test clamping at the upper bound: conditions that produce no deductions should yield 100.
		 */
		@Test
		void testClampingScoreUpperBound() {
			WeatherData weatherData = new WeatherData();
			weatherData.setWindSpeed(0.0);
			weatherData.setAirTemperature(25.0);
			weatherData.setPhenomenon("clear");
			int score = RiskScoreCalculator.computeRiskScore(weatherData, "Car");
			assertEquals(100, score, "Score should remain 100 if no deductions apply.");
		}

		/**
		 * Test behavior for an invalid vehicle type.
		 */
		@Test
		void testInvalidVehicle() {
			WeatherData weatherData = new WeatherData();
			weatherData.setWindSpeed(10.0);
			weatherData.setAirTemperature(20.0);
			weatherData.setPhenomenon("clear");

			Exception exception = assertThrows(IllegalArgumentException.class,
					() -> RiskScoreCalculator.computeRiskScore(weatherData, "Submarine"));

			// Check that the exception message contains "SUBMARINE" (which is what valueOf returns)
			assertTrue(exception.getMessage().contains("SUBMARINE"),
					"Exception should mention the invalid vehicle type 'SUBMARINE'");
		}
	}

	// Tests for integration
	@Nested
	@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
	public class PricingControllerIntegrationTest {

		@Autowired
		private TestRestTemplate restTemplate;

		@Autowired
		private WeatherDataRepository weatherDataRepository;

		@BeforeEach
		void setUp() {
			// Clean the repository before each test.
			weatherDataRepository.deleteAll();
		}

		@Test
		void testGetRidePriceEndpoint_TallinnAndCar() {
			// Arrange: Insert a WeatherData record that matches the "Tallinn" city.
			WeatherData testData = new WeatherData();
			testData.setStationName("Tallinn-Harku");
			testData.setAirTemperature(5.0);
			testData.setWindSpeed(5.0);
			testData.setPhenomenon("clear");
			testData.setObservationTimestamp(LocalDateTime.now());
			weatherDataRepository.save(testData);

			// Act: Call the API endpoint. The endpoint is assumed to be mapped as "/ride-price"
			String url = "/ride-price?city=Tallinn&vehicle=Car";
			ResponseEntity<RidePriceEstimate> response = restTemplate.getForEntity(url, RidePriceEstimate.class);

			// Assert: Verify the response status and content.
			assertEquals(HttpStatus.OK, response.getStatusCode());
			RidePriceEstimate estimate = response.getBody();
			assertNotNull(estimate);
			// For a Car in Tallinn with these normal conditions, the base fee is 4.0, no extra fee, so final price should be 4.0.
			assertEquals(4.0, estimate.getFinalPrice(), 0.001, "Final price should match expected fee.");
		}

		@Test
		void testGetRidePriceEndpoint_NoWeatherData() {
			// Arrange: No weather data is added.
			String url = "/ride-price?city=Tallinn&vehicle=Car";

			// Act & Assert: Expect an error response (HTTP 400 or 500 depending on your implementation).
			ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
			assertNotEquals(HttpStatus.OK, response.getStatusCode(), "Expected error status when no weather data available.");
			// Optionally, you can assert that the error message contains the expected text.
			assertTrue(response.getBody().contains("No weather data available for city: Tallinn"));
		}
	}
	@Nested
	@SpringBootTest
	public class ApiWeatherIntegrationTest {

		@Autowired
		private WeatherDataRepository weatherDataRepository;

		@Autowired
		private ApiWeather apiWeather;

		@Autowired
		private RestTemplate restTemplate;

		private MockRestServiceServer mockServer;

		@BeforeEach
		void setup() {
			weatherDataRepository.deleteAll();
			mockServer = MockRestServiceServer.createServer(restTemplate);
			// Override the private restTemplate field in apiWeather with the test restTemplate:
			ReflectionTestUtils.setField(apiWeather, "restTemplate", restTemplate);
		}

		@Test
		void testImportWeatherData_success() throws Exception {
			// Prepare sample XML responses for each city in the format provided by WeatherAPI.com.
			String xmlResponseTallinn = """
            <?xml version="1.0" encoding="UTF-8"?>
            <root>
                <location>
                    <name>Tallinn</name>
                </location>
                <current>
                    <temp_c>5.0</temp_c>
                    <wind_kph>18.0</wind_kph>
                    <condition>
                        <text>Clear</text>
                    </condition>
                </current>
            </root>
            """;
			String xmlResponseTartu = """
            <?xml version="1.0" encoding="UTF-8"?>
            <root>
                <location>
                    <name>Tartu</name>
                </location>
                <current>
                    <temp_c>3.0</temp_c>
                    <wind_kph>10.8</wind_kph>
                    <condition>
                        <text>Rain</text>
                    </condition>
                </current>
            </root>
            """;
			String xmlResponseParnu = """
            <?xml version="1.0" encoding="UTF-8"?>
            <root>
                <location>
                    <name>Pärnu</name>
                </location>
                <current>
                    <temp_c>4.0</temp_c>
                    <wind_kph>14.4</wind_kph>
                    <condition>
                        <text>Cloudy</text>
                    </condition>
                </current>
            </root>
            """;

			// Base URL for WeatherAPI.com's current.xml endpoint
			String baseUrl = "http://api.weatherapi.com/v1/current.xml?key=20ac311827124d0ba50125028250404&q=";
			// Note: URL-encode Pärnu properly.
			String urlTallinn = baseUrl + "Tallinn&aqi=no";
			String urlTartu = baseUrl + "Tartu&aqi=no";
			String urlParnu = baseUrl + "P%C3%A4rnu&aqi=no";

			// Set up the mock server expectations for each API call
			mockServer.expect(ExpectedCount.once(), requestTo(urlTallinn))
					.andExpect(method(HttpMethod.GET))
					.andRespond(withSuccess(xmlResponseTallinn, MediaType.APPLICATION_XML));

			mockServer.expect(ExpectedCount.once(), requestTo(urlTartu))
					.andExpect(method(HttpMethod.GET))
					.andRespond(withSuccess(xmlResponseTartu, MediaType.APPLICATION_XML));

			mockServer.expect(ExpectedCount.once(), requestTo(urlParnu))
					.andExpect(method(HttpMethod.GET))
					.andRespond(withSuccess(xmlResponseParnu, MediaType.APPLICATION_XML));

			// Call the method under test. This should loop over all three cities.
			apiWeather.importWeatherData();

			// Verify that weather data was saved in the repository.
			List<WeatherData> savedData = weatherDataRepository.findAll();
			assertFalse(savedData.isEmpty(), "Weather data should have been saved");
			assertEquals(3, savedData.size(), "Expected weather data for three cities");

			// Optionally, assert that the expected data is present.
			Optional<WeatherData> tallinnDataOpt = savedData.stream()
					.filter(data -> "Tallinn".equals(data.getStationName()))
					.findFirst();
			assertTrue(tallinnDataOpt.isPresent(), "Tallinn weather data should be present");
			WeatherData tallinnData = tallinnDataOpt.get();
			assertEquals(5.0, tallinnData.getAirTemperature(), 0.001, "Tallinn temperature should be 5.0");

			// Similarly, you can add assertions for Tartu and Pärnu if desired.

			// Verify the mock server expectations.
			mockServer.verify();
		}
	}


}



