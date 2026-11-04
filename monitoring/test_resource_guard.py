import unittest
from resource_guard import stop_reason


class ResourceGuardTest(unittest.TestCase):
    def test_resource_limits(self):
        before = dict(at=100, availableKiB=280000, swapInPages=100, swapOutPages=200,
                      pageBytes=4096, memorySomeAvg10=0)
        normal = {**before, 'at': 105}
        self.assertIsNone(stop_reason(before, normal))
        # Idle observation also showed a few page-ins; that alone isn't the pressure guard.
        self.assertIsNone(stop_reason(before, {**normal, 'swapInPages': 135}))
        # 2026-11-04 운영 평소 상태: 5초에 swap-out 20페이지(80KiB)는 압박으로 보지 않는다.
        self.assertIsNone(stop_reason(before, {**normal, 'swapOutPages': 220}))
        for changed in [dict(availableKiB=102399), dict(swapOutPages=1480),
                        dict(swapInPages=1380), dict(memorySomeAvg10=1), dict(at=100)]:
            with self.subTest(changed=changed):
                self.assertIsNotNone(stop_reason(before, {**normal, **changed}))


if __name__ == '__main__':
    unittest.main()
