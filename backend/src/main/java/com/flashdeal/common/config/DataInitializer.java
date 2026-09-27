package com.flashdeal.common.config;

import com.flashdeal.auth.User;
import com.flashdeal.auth.UserRepository;
import com.flashdeal.product.Product;
import com.flashdeal.product.ProductRepository;
import com.flashdeal.product.SubCategory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.flashdeal.product.SubCategory.*;

/**
 * 데모 계정과 상품을 넣는다. 이미 데이터가 있으면 건너뛴다.
 * 특가는 기동 시각 기준 상대 시간으로 만들어서, 언제 실행해도 "진행 중 / 오픈 전 / 품절 / 마감 임박" 상태가 모두 보이게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "flashdeal.seed.enabled", havingValue = "true")
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Value("${flashdeal.seed.admin-password:admin}")
    private String adminPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }
        // 데모 편의를 위한 admin/admin. 운영이라면 ADMIN_PASSWORD 환경변수로 반드시 바꿔야 한다
        userRepository.save(new User("admin", "admin@flashdeal.com", passwordEncoder.encode(adminPassword), "관리자", User.Role.ADMIN));
        userRepository.save(new User("user1", "user1@flashdeal.com", passwordEncoder.encode("user1234!"), "김철수", User.Role.USER));
        userRepository.save(new User("user2", "user2@flashdeal.com", passwordEncoder.encode("user1234!"), "이영희", User.Role.USER));

        List<Product> products = products(LocalDateTime.now(clock));
        attachImages(products);
        productRepository.saveAll(products);
        log.info("event=SEED_DONE users=3 products={}", products.size());
    }

    private List<Product> products(LocalDateTime now) {
        List<Product> list = new ArrayList<>();

        // ── 키보드 ──
        list.add(deal(now, "[한정 100개] 무접점 기계식 키보드 한정판", KEYBOARD_MECHANICAL, 89_000, 159_000,
                100, null,
                "오늘만 이 가격, 선착순 100개",
                "정전용량 무접점 방식으로 부드럽고 조용한 타건감을 제공합니다. 한정판 컬러 키캡과 알루미늄 보강판을 적용했습니다.",
                specs("스위치", "정전용량 무접점 45g", "배열", "텐키리스 87키", "연결", "USB-C 유선", "키캡", "PBT 이중사출")));
        list.add(normal("저소음 무선 키보드", KEYBOARD_WIRELESS, 29_000, 500,
                "사무실용 저소음 적축",
                "도서관처럼 조용한 사무실에서도 부담 없는 저소음 적축 키보드입니다.",
                specs("스위치", "저소음 적축", "배열", "풀배열 104키", "연결", "2.4GHz 무선", "배터리", "AA 2개 (최대 12개월)")));
        list.add(normal("게이밍 텐키리스 키보드", KEYBOARD_GAMING, 54_000, 300,
                "RGB 게이밍 키보드",
                "빠른 입력이 필요한 FPS 게임을 위한 텐키리스 게이밍 키보드입니다. 1000Hz 폴링을 지원합니다.",
                specs("스위치", "리니어 적축", "배열", "텐키리스 87키", "폴링", "1000Hz", "조명", "RGB")));
        list.add(normal("알루미늄 풀배열 기계식 키보드", KEYBOARD_MECHANICAL, 119_000, 80,
                "CNC 알루미늄 하우징",
                "통 알루미늄 하우징과 가스켓 마운트로 묵직하고 단단한 타건음을 냅니다.",
                specs("스위치", "택타일 갈축", "배열", "풀배열 104키", "하우징", "CNC 알루미늄", "마운트", "가스켓")));
        list.add(deal(now, "슬림 블루투스 멀티페어링 키보드", KEYBOARD_WIRELESS, 39_000, 59_000,
                50, 1,
                "기기 3대 전환, 오픈 예정 특가",
                "노트북, 태블릿, 스마트폰을 버튼 하나로 전환하는 슬림 블루투스 키보드입니다.",
                specs("연결", "블루투스 5.1 (3대 페어링)", "두께", "14mm", "배터리", "충전식 (최대 3개월)", "배열", "텐키리스")));

        // ── 마우스 ──
        list.add(normal("무선 버티컬 마우스", MOUSE_WIRELESS, 19_900, 400,
                "손목 편한 인체공학 마우스",
                "57도 기울기로 손목 비틀림을 줄여주는 버티컬 마우스입니다.",
                specs("연결", "2.4GHz 무선", "DPI", "800 / 1200 / 1600", "버튼", "6개", "배터리", "AA 1개")));
        list.add(normal("초경량 게이밍 마우스", MOUSE_GAMING, 45_000, 200,
                "58g 초경량 게이밍 무선 마우스",
                "허니콤 없이 58g을 달성한 초경량 무선 게이밍 마우스입니다.",
                specs("무게", "58g", "센서", "26K DPI 광학", "연결", "2.4GHz 무선 / USB-C", "배터리", "최대 70시간")));
        list.add(deal(now, "저소음 무선 마우스", MOUSE_WIRELESS, 15_900, 25_900,
                200, 2,
                "클릭 소음 90% 감소, 1인 2개",
                "클릭 소리를 크게 줄인 저소음 스위치를 적용한 무선 마우스입니다.",
                specs("연결", "2.4GHz 무선", "DPI", "1000", "스위치", "저소음 마이크로 스위치", "배터리", "AA 1개 (최대 18개월)")));
        list.add(normal("8K 폴링 게이밍 마우스", MOUSE_GAMING, 79_000, 60,
                "8000Hz 초고속 응답",
                "8000Hz 폴링레이트로 입력 지연을 최소화한 하이엔드 게이밍 마우스입니다.",
                specs("폴링", "8000Hz", "센서", "30K DPI 광학", "무게", "63g", "연결", "유선 / 무선")));
        list.add(normal("무선 트랙볼 마우스", MOUSE_WIRELESS, 49_000, 90,
                "손목을 움직이지 않는 트랙볼",
                "엄지로 볼을 굴려 조작하는 트랙볼 마우스입니다. 좁은 책상에서도 편하게 쓸 수 있습니다.",
                specs("연결", "블루투스 / 2.4GHz", "볼", "34mm", "배터리", "충전식", "버튼", "5개")));

        // ── 모니터 ──
        list.add(normal("27인치 QHD 게이밍 모니터", MONITOR_GAMING, 219_000, 50,
                "IPS 165Hz",
                "QHD 해상도와 165Hz 주사율을 갖춘 27인치 IPS 게이밍 모니터입니다.",
                specs("크기", "27인치", "해상도", "2560x1440", "주사율", "165Hz", "패널", "IPS")));
        list.add(normal("24인치 사무용 모니터", MONITOR_OFFICE, 129_000, 80,
                "눈 편한 플리커프리",
                "플리커프리와 블루라이트 감소 모드를 지원하는 사무용 모니터입니다.",
                specs("크기", "24인치", "해상도", "1920x1080", "주사율", "75Hz", "패널", "IPS")));
        list.add(deal(now, "32인치 4K 모니터", MONITOR_OFFICE, 389_000, 499_000,
                30, 1,
                "4K UHD, 조기 품절",
                "넓은 작업 공간이 필요한 디자이너와 개발자를 위한 32인치 4K 모니터입니다.",
                specs("크기", "32인치", "해상도", "3840x2160", "주사율", "60Hz", "패널", "VA")));
        list.add(normal("24인치 240Hz 게이밍 모니터", MONITOR_GAMING, 259_000, 40,
                "e스포츠용 240Hz",
                "0.5ms 응답속도와 240Hz 주사율로 e스포츠 환경에 최적화된 모니터입니다.",
                specs("크기", "24인치", "해상도", "1920x1080", "주사율", "240Hz", "응답속도", "0.5ms")));

        // ── 오디오 ──
        list.add(normal("노이즈캔슬링 무선 헤드셋", AUDIO_HEADSET, 99_000, 120,
                "ANC 블루투스 헤드셋",
                "액티브 노이즈캔슬링으로 주변 소음을 줄여 집중력을 높여줍니다.",
                specs("연결", "블루투스 5.3", "ANC", "지원", "배터리", "최대 40시간", "무게", "250g")));
        list.add(normal("무선 이어폰", AUDIO_EARPHONE, 39_000, 300,
                "가성비 블루투스 이어폰",
                "출퇴근길에 가볍게 쓰기 좋은 가성비 무선 이어폰입니다.",
                specs("연결", "블루투스 5.3", "배터리", "이어폰 6시간 + 케이스 24시간", "방수", "IPX4", "코덱", "AAC")));
        list.add(normal("데스크 스피커", AUDIO_SPEAKER, 49_000, 150,
                "2채널 PC 스피커",
                "책상 위에 두기 좋은 컴팩트한 2채널 PC 스피커입니다.",
                specs("채널", "2.0", "출력", "20W", "입력", "3.5mm / USB", "크기", "90x150x100mm")));
        list.add(deal(now, "7.1채널 게이밍 헤드셋", AUDIO_HEADSET, 69_000, 99_000,
                80, 1,
                "마감 임박! 1인 1개",
                "가상 7.1채널 서라운드로 발소리 방향을 정확하게 들을 수 있는 게이밍 헤드셋입니다.",
                specs("채널", "가상 7.1", "드라이버", "50mm", "마이크", "탈착식 단일지향성", "연결", "USB")));
        list.add(normal("노이즈캔슬링 무선 이어폰", AUDIO_EARPHONE, 129_000, 70,
                "하이브리드 ANC 이어폰",
                "하이브리드 ANC와 공간 음향을 지원하는 프리미엄 무선 이어폰입니다.",
                specs("연결", "블루투스 5.3", "ANC", "하이브리드", "배터리", "이어폰 8시간 + 케이스 30시간", "방수", "IPX5")));
        list.add(normal("블루투스 포터블 스피커", AUDIO_SPEAKER, 59_000, 110,
                "IP67 방수 야외용",
                "캠핑이나 피크닉에 들고 가기 좋은 방수 블루투스 스피커입니다.",
                specs("출력", "16W", "방수", "IP67", "배터리", "최대 15시간", "연결", "블루투스 5.3")));

        // ── 액세서리 ──
        list.add(normal("대형 장패드", ACCESSORY_PAD, 12_900, 1000,
                "900x400 데스크 매트",
                "키보드와 마우스를 모두 올릴 수 있는 대형 데스크 매트입니다.",
                specs("크기", "900x400mm", "두께", "4mm", "마감", "오버로크 스티칭", "바닥", "논슬립 고무")));
        list.add(normal("모니터 거치대 암", ACCESSORY_STAND, 25_000, 300,
                "높이 조절 모니터 암",
                "책상 공간을 넓혀주는 가스 스프링 모니터 암입니다.",
                specs("지원 크기", "17~32인치", "하중", "2~9kg", "VESA", "75x75 / 100x100", "설치", "클램프 / 그로밋")));
        list.add(normal("알루미늄 노트북 거치대", ACCESSORY_STAND, 32_000, 200,
                "6단 각도 조절",
                "노트북 화면을 눈높이로 올려 거북목을 예방하는 알루미늄 거치대입니다.",
                specs("소재", "알루미늄", "각도", "6단 조절", "지원", "10~17인치", "무게", "600g")));
        list.add(normal("PBT 키캡 세트", ACCESSORY_ETC, 29_000, 150,
                "체리 프로파일 135키",
                "번들거림이 적은 PBT 소재의 체리 프로파일 키캡 세트입니다.",
                specs("소재", "PBT 이중사출", "프로파일", "체리", "구성", "135키", "호환", "MX 스위치")));
        list.add(deal(now, "USB-C 7in1 멀티 허브", ACCESSORY_ETC, 35_000, 49_000,
                120, 2,
                "HDMI 4K + PD 100W, 오픈 예정",
                "노트북 하나로 모니터, 키보드, 마우스, 충전을 모두 연결하는 멀티 허브입니다.",
                specs("포트", "HDMI, USB-A x3, USB-C, SD, microSD", "HDMI", "4K 30Hz", "충전", "PD 100W", "소재", "알루미늄")));
        return list;
    }

    /** 시드 순서와 1:1 대응하는 이미지 슬러그. 경로는 scripts/fetch-product-images.mjs가 만든 manifest에서 읽는다 */
    private static final List<String> IMAGE_SLUGS = List.of(
            "kb-limited", "kb-silent-wireless", "kb-gaming-tkl", "kb-aluminum", "kb-slim-bt",
            "ms-vertical", "ms-light-gaming", "ms-silent", "ms-8k", "ms-trackball",
            "mn-27-qhd", "mn-24-office", "mn-32-4k", "mn-24-240",
            "au-anc-headset", "au-earbuds", "au-desk-speaker", "au-gaming-headset", "au-anc-earbuds", "au-portable-speaker",
            "ac-deskmat", "ac-monitor-arm", "ac-laptop-stand", "ac-keycaps", "ac-usb-hub");

    private void attachImages(List<Product> products) {
        Map<String, List<String>> manifest = readManifest();
        for (int i = 0; i < products.size() && i < IMAGE_SLUGS.size(); i++) {
            products.get(i).replaceImages(manifest.getOrDefault(IMAGE_SLUGS.get(i), List.of()));
        }
    }

    /** manifest가 없어도 앱은 떠야 한다. 이미지가 없으면 프론트가 카테고리 플레이스홀더를 보여준다 */
    private Map<String, List<String>> readManifest() {
        try (InputStream in = new ClassPathResource("seed/product-images.json").getInputStream()) {
            return objectMapper.readValue(in, new TypeReference<>() {
            });
        } catch (IOException e) {
            log.warn("event=SEED_IMAGES_MISSING cause={}", e.getMessage());
            return Map.of();
        }
    }

    private Product normal(String name, SubCategory sub, long price, int stock, String desc, String detail,
                           Map<String, String> specs) {
        return Product.builder().name(name).subCategory(sub).price(price).stock(stock)
                .description(desc).detail(detail).specs(specs).build();
    }

    /** 특가 시간과 초기 재고는 DemoDeals 시간표에서 가져온다 (시연용 재편성 작업과 같은 표) */
    private Product deal(LocalDateTime now, String name, SubCategory sub, long price, long originalPrice,
                         int dealQuantity, Integer perUserLimit,
                         String desc, String detail, Map<String, String> specs) {
        DemoDeals.Slot slot = DemoDeals.slot(name);
        return Product.builder().name(name).subCategory(sub).price(price).originalPrice(originalPrice).stock(slot.stock())
                .dealStartAt(slot.start(now)).dealEndAt(slot.end(now)).dealQuantity(dealQuantity).perUserLimit(perUserLimit)
                .description(desc).detail(detail).specs(specs).build();
    }

    private static Map<String, String> specs(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put(kv[i], kv[i + 1]);
        return map;
    }
}
