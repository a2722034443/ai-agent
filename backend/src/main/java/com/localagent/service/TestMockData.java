package com.localagent.service;

import com.localagent.model.Poi;
import com.localagent.model.PoiType;
import com.localagent.repo.PoiRepository;
import java.util.List;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("test")
public class TestMockData implements CommandLineRunner {
    private final PoiRepository poiRepository;

    public TestMockData(PoiRepository poiRepository) {
        this.poiRepository = poiRepository;
    }

    @Override
    public void run(String... args) {
        if (poiRepository.count() > 0) {
            return;
        }
        poiRepository.saveAll(List.of(
                new Poi("大连亲子乐园", PoiType.ENTERTAINMENT, "亲子乐园", "星海湾", 121.588, 38.883, 95, 88, 4.8, true, false, true, false, false, false),
                new Poi("大连科学剧场", PoiType.CULTURE, "儿童剧场", "会展中心", 121.592, 38.890, 80, 68, 4.6, true, false, true, false, true, false),
                new Poi("绿园轻食餐厅", PoiType.DINING, "轻食餐厅", "星海南门", 121.586, 38.879, 75, 92, 4.7, true, true, true, true, false, false),
                new Poi("家庭海鲜餐厅", PoiType.DINING, "海鲜餐厅", "星海湾一号", 121.582, 38.881, 85, 138, 4.6, true, false, true, true, false, true),
                new Poi("海风步道", PoiType.EXTRA, "海边散步", "星海海边", 121.590, 38.875, 70, 0, 4.5, true, true, false, true, false, false),
                new Poi("沉浸式密室", PoiType.ENTERTAINMENT, "密室", "和平广场", 121.606, 38.901, 110, 128, 4.8, false, false, true, true, false, false),
                new Poi("新影艺术馆", PoiType.CULTURE, "展览", "高新区", 121.533, 38.861, 90, 78, 4.7, false, false, true, true, true, false),
                new Poi("四人烧烤餐厅", PoiType.DINING, "聚餐餐厅", "和平广场", 121.604, 38.899, 90, 118, 4.6, false, false, true, true, false, false),
                new Poi("甜品补给站", PoiType.EXTRA, "甜品", "和平步行街", 121.608, 38.898, 60, 38, 4.4, true, false, true, true, false, false)
        ));
    }
}
