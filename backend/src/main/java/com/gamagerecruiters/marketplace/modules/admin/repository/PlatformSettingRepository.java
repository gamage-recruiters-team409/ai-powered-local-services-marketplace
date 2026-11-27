package com.gamagerecruiters.marketplace.modules.admin.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.gamagerecruiters.marketplace.modules.admin.domain.PlatformSetting;

@Repository
public interface PlatformSettingRepository extends MongoRepository<PlatformSetting, String> {

    List<PlatformSetting> findByGroupOrderByKeyAsc(String group);

    Optional<PlatformSetting> findByKey(String key);

    List<PlatformSetting> findAllByOrderByGroupAscKeyAsc();
}
