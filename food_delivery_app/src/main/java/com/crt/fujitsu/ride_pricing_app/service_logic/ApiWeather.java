package com.crt.fujitsu.ride_pricing_app.service_logic;

import com.crt.fujitsu.ride_pricing_app.model.WeatherData;
import com.crt.fujitsu.ride_pricing_app.repository.WeatherDataRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class ApiWeather {

    private final WeatherDataRepository weatherDataRepository;
    private final RestTemplate restTemplate;
    // Your WeatherAPI.com key:
    private final String apiKey = "20ac311827124d0ba50125028250404";

    public ApiWeather(WeatherDataRepository weatherDataRepository, RestTemplate restTemplate) {
        this.weatherDataRepository = weatherDataRepository;
        this.restTemplate = restTemplate;
    }

    // Run every hour at the 15th minute (for testing, we're using fixedRate = 5000 ms)
    @Scheduled(fixedRate = 5000)
    public void importWeatherData() {
        System.out.println("importWeatherData called");
        try {
            // Define the cities you want to fetch data for.
            List<String> cities = Arrays.asList("Tallinn", "Tartu", "Pärnu");
            List<WeatherData> weatherDataList = new ArrayList<>();
            LocalDateTime observationTime = LocalDateTime.now();

            for (String city : cities) {
                // Build the URL for WeatherAPI.com's current.xml endpoint.
                String url = "http://api.weatherapi.com/v1/current.xml?key=" + apiKey +
                        "&q=" + city + "&aqi=no";

                // Set up headers if needed (WeatherAPI often works with just a GET).
                HttpHeaders headers = new HttpHeaders();
                headers.set("Accept", MediaType.APPLICATION_XML_VALUE);
                HttpEntity<String> entity = new HttpEntity<>(headers);

                ResponseEntity<String> response = restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        entity,
                        String.class
                );
                String xmlData = response.getBody();

                Document document = parseXmlString(xmlData);

                // Extract data from the XML:
                String stationName = getElementTextContent(document, "name"); // from <location>
                // WeatherAPI does not provide a WMO code, so you can set it as empty or null.
                String wmoCode = "";
                // Temperature (in °C) from <current><temp_c>
                Double airTemperature = parseDoubleOrNull(getElementTextContent(document, "temp_c"));
                // Wind speed is provided in km/h; convert to m/s (1 m/s = 3.6 km/h)
                Double windSpeedKph = parseDoubleOrNull(getElementTextContent(document, "wind_kph"));
                Double windSpeed = windSpeedKph != null ? windSpeedKph / 3.6 : null;
                // Phenomenon from <current><condition><text>
                String phenomenon = getNestedElementTextContent(document, "condition", "text");

                WeatherData data = new WeatherData(
                        stationName,
                        wmoCode,
                        airTemperature,
                        windSpeed,
                        phenomenon,
                        observationTime
                );

                weatherDataList.add(data);
            }

            weatherDataRepository.saveAll(weatherDataList);

        } catch (Exception e) {
            System.err.println("Error importing weather data: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private Document parseXmlString(String xmlString) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xmlString)));
    }

    private String getElementTextContent(Document document, String tagName) {
        NodeList list = document.getElementsByTagName(tagName);
        if (list.getLength() > 0) {
            return list.item(0).getTextContent();
        }
        return null;
    }

    // Helper method to get text content from a nested element (e.g., <condition><text>)
    private String getNestedElementTextContent(Document document, String parentTag, String childTag) {
        NodeList parentList = document.getElementsByTagName(parentTag);
        if (parentList.getLength() > 0) {
            NodeList childList = ((org.w3c.dom.Element) parentList.item(0)).getElementsByTagName(childTag);
            if (childList.getLength() > 0) {
                return childList.item(0).getTextContent();
            }
        }
        return null;
    }

    private Double parseDoubleOrNull(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
