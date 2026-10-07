package com.golf.course.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.golf.course.entity.Course;

public interface CourseRepository extends JpaRepository<Course, Long> {

    Optional<Course> findByCourseNo(String courseNo);
}
