package com.trova.backend.weather;

public interface OpenWeatherApiClient {
    OpenWeatherForecastResponse forecast(double latitude, double longitude);
}
