package org.example.mcp.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for Open-Meteo API (free, no API key required)
 * https://open-meteo.com/
 */
class WeatherApi {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Get current weather for a location
     */
    fun getCurrentWeather(latitude: Double, longitude: Double): WeatherResult {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?" +
                "latitude=$latitude&longitude=$longitude" +
                "&current_weather=true" +
                "&timezone=auto"
        )

        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        return try {
            val responseText = connection.inputStream.bufferedReader().readText()
            val response = json.decodeFromString(OpenMeteoResponse.serializer(), responseText)

            WeatherResult.Success(
                temperature = response.currentWeather.temperature,
                windSpeed = response.currentWeather.windspeed,
                windDirection = response.currentWeather.winddirection,
                weatherCode = response.currentWeather.weathercode,
                weatherDescription = describeWeatherCode(response.currentWeather.weathercode),
                isDay = response.currentWeather.isDay == 1,
                time = response.currentWeather.time
            )
        } catch (e: Exception) {
            WeatherResult.Error("Failed to get weather: ${e.message}")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Get weather forecast for multiple days
     * @param days Number of days (1-16)
     */
    fun getForecast(latitude: Double, longitude: Double, days: Int = 7): ForecastResult {
        val forecastDays = days.coerceIn(1, 16)
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?" +
                "latitude=$latitude&longitude=$longitude" +
                "&daily=weathercode,temperature_2m_max,temperature_2m_min,precipitation_sum,windspeed_10m_max" +
                "&timezone=auto" +
                "&forecast_days=$forecastDays"
        )

        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        return try {
            val responseText = connection.inputStream.bufferedReader().readText()
            val response = json.decodeFromString(ForecastResponse.serializer(), responseText)

            val dailyData = response.daily
            val forecasts = dailyData.time.mapIndexed { index, date ->
                DayForecast(
                    date = date,
                    tempMax = dailyData.temperatureMax[index],
                    tempMin = dailyData.temperatureMin[index],
                    precipitation = dailyData.precipitation[index],
                    windSpeedMax = dailyData.windSpeedMax[index],
                    weatherCode = dailyData.weatherCode[index],
                    weatherDescription = describeWeatherCode(dailyData.weatherCode[index])
                )
            }

            ForecastResult.Success(forecasts)
        } catch (e: Exception) {
            ForecastResult.Error("Failed to get forecast: ${e.message}")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Get coordinates for a city name using Open-Meteo Geocoding API
     */
    fun geocodeCity(cityName: String): GeocodingResult {
        val encodedCity = java.net.URLEncoder.encode(cityName, "UTF-8")
        val url = URL("https://geocoding-api.open-meteo.com/v1/search?name=$encodedCity&count=1&language=en&format=json")

        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        return try {
            val responseText = connection.inputStream.bufferedReader().readText()
            val response = json.decodeFromString(GeocodingResponse.serializer(), responseText)

            val result = response.results?.firstOrNull()
            if (result != null) {
                GeocodingResult.Success(
                    name = result.name,
                    country = result.country,
                    latitude = result.latitude,
                    longitude = result.longitude
                )
            } else {
                GeocodingResult.NotFound("City not found: $cityName")
            }
        } catch (e: Exception) {
            GeocodingResult.Error("Geocoding failed: ${e.message}")
        } finally {
            connection.disconnect()
        }
    }

    private fun describeWeatherCode(code: Int): String = when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Foggy"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75 -> "Snowfall"
        77 -> "Snow grains"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Unknown"
    }
}

// Response DTOs

@Serializable
data class OpenMeteoResponse(
    @SerialName("current_weather") val currentWeather: CurrentWeather
)

@Serializable
data class CurrentWeather(
    val temperature: Double,
    val windspeed: Double,
    val winddirection: Double,
    val weathercode: Int,
    @SerialName("is_day") val isDay: Int,
    val time: String
)

@Serializable
data class GeocodingResponse(
    val results: List<GeocodingLocation>? = null
)

@Serializable
data class GeocodingLocation(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val country: String? = null
)

// Result types

sealed class WeatherResult {
    data class Success(
        val temperature: Double,
        val windSpeed: Double,
        val windDirection: Double,
        val weatherCode: Int,
        val weatherDescription: String,
        val isDay: Boolean,
        val time: String
    ) : WeatherResult()

    data class Error(val message: String) : WeatherResult()
}

sealed class GeocodingResult {
    data class Success(
        val name: String,
        val country: String?,
        val latitude: Double,
        val longitude: Double
    ) : GeocodingResult()

    data class NotFound(val message: String) : GeocodingResult()
    data class Error(val message: String) : GeocodingResult()
}

// Forecast DTOs

@Serializable
data class ForecastResponse(
    val daily: DailyData
)

@Serializable
data class DailyData(
    val time: List<String>,
    @SerialName("weathercode") val weatherCode: List<Int>,
    @SerialName("temperature_2m_max") val temperatureMax: List<Double>,
    @SerialName("temperature_2m_min") val temperatureMin: List<Double>,
    @SerialName("precipitation_sum") val precipitation: List<Double>,
    @SerialName("windspeed_10m_max") val windSpeedMax: List<Double>
)

data class DayForecast(
    val date: String,
    val tempMax: Double,
    val tempMin: Double,
    val precipitation: Double,
    val windSpeedMax: Double,
    val weatherCode: Int,
    val weatherDescription: String
)

sealed class ForecastResult {
    data class Success(val days: List<DayForecast>) : ForecastResult()
    data class Error(val message: String) : ForecastResult()
}