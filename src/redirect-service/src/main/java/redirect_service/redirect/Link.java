package redirect_service.redirect;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * redirect-service가 조회에 필요한 links 테이블 컬럼만 매핑한다.
 * 테이블의 생성과 변경은 db 모듈의 Flyway migration이 관리한다.
 */
@Entity
@Table(name = "links")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Link {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "slug", nullable = false, length = 20)
    private String slug;

    @Column(name = "original_url", nullable = false, length = 2048)
    private String originalUrl;

    @Column(name = "is_visible", nullable = false)
    private boolean isVisible;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    public boolean isRedirectable(LocalDateTime now) {
        return isEnabled() && (expiresAt == null || now.isBefore(expiresAt));
    }

    /**
     * 현재는 soft delete 여부만 확인한다.
     * 활성화/비활성화 기능이 추가되면 해당 상태까지 이 메서드에서 함께 판단한다.
     */
    public boolean isEnabled() {
        return isVisible;
    }
}
