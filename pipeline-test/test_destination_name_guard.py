import unittest
from extract_places import exclude_known_non_destinations, keep_destination_name


class DestinationNameGuardTest(unittest.TestCase):
    def test_명시적으로_제외한_넓은_지역명은_영문과_전각표기도_제외한다(self):
        for name in ['부산','Busan','ＢＵＳＡＮ','서울특별시','Seoul','제주도','Jeju Island']:
            with self.subTest(name=name):self.assertFalse(keep_destination_name(name))

    def test_그_이름을_포함한_구체적인_장소는_유지한다(self):
        for name in ['부산역','서울숲','제주도립미술관','Busan Museum','해운대','기차카페','KTX 부산역']:
            with self.subTest(name=name):self.assertTrue(keep_destination_name(name))

    def test_고정_지도_지점이_아닌_차량_내부는_제외한다(self):
        for name in ['SRT 기차안','KTX 열차 내부','기차 안','지하철 안','기내']:
            with self.subTest(name=name):self.assertFalse(keep_destination_name(name))

    def test_유효_장소의_메모와_대안_이름과_일정_필드는_그대로_유지한다(self):
        place={'name':'서울숲','region':'서울','dayNumber':2,'nameCandidates':['서울숲'],'videoNotes':['예약 필요']}
        output=exclude_known_non_destinations([{'name':'Busan'},place,{'name':'SRT 기차안'}])
        self.assertEqual(output,[place]);self.assertIs(output[0],place)

    def test_도시와_같은_상호에_가게나_주소_근거가_있으면_유지한다(self):
        places=[{'name':'Seoul','category':'restaurant'}, {'name':'Busan','address':'구체적인 주소'}]
        self.assertEqual(exclude_known_non_destinations(places),places)

    def test_다른_행정구역이나_별칭을_추측해서_추가_제외하지_않는다(self):
        self.assertTrue(keep_destination_name('도쿄'))


if __name__=='__main__':unittest.main()
