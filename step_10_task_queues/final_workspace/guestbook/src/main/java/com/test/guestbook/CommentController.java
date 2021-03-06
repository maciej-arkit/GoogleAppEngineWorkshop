/**
 * 
 */
package com.test.guestbook;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import com.google.appengine.api.blobstore.BlobKey;
import com.google.appengine.api.blobstore.BlobstoreService;
import com.google.appengine.api.blobstore.BlobstoreServiceFactory;
import com.google.appengine.api.images.ImagesService;
import com.google.appengine.api.images.ImagesServiceFactory;
import com.google.appengine.api.images.ServingUrlOptions;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.api.taskqueue.TaskOptions.Method;
import com.test.guestbook.dao.CommentDAO;
import com.test.guestbook.domain.Comment;

/**
 * @author Maciek
 *
 */
@Controller
public class CommentController {
	
	private static final Logger LOGGER = Logger.getLogger(CommentController.class.getName());
	
	@Autowired
	private CommentDAO commentDAO;
	
	@Autowired
	private Integer commentsRetentionMinutes;
	
	/**
	 * Returns all comments
	 * @return
	 */
	@RequestMapping(value="/getAllComments", method=RequestMethod.GET)
	public ModelAndView getAllComments() {
		ModelAndView result = new ModelAndView("guestbook");
		
		LOGGER.info("/getAllComments");
		
		List<Comment> comments = this.commentDAO.getAllComments();
		result.addObject("comments", comments);
		
		//Providing placeholder for new comment
		result.addObject("newComment", prepareNewCommentPlaceholder());
		
		return result;
		
	}
	
	
	/**
	 * Returns comments for given user
	 * @return
	 */
	@RequestMapping(value="/getCommentsForUser", method=RequestMethod.GET)
	public ModelAndView getCommentsForUser(@RequestParam(value="userEmail", required=true) String userEmail) {
		ModelAndView result = new ModelAndView("guestbook");
		
		LOGGER.info("/getCommentsForUser");
		
		List<Comment> comments = this.commentDAO.getComments(userEmail);
		result.addObject("comments", comments);
		
		//Providing placeholder for new comment 
		result.addObject("newComment", prepareNewCommentPlaceholder());
		
		return result;
	}
	
	/**
	 * Adds a new comment
	 * @param request
	 * @param newComment
	 * @return
	 */
	@RequestMapping(value="/addComment", method=RequestMethod.POST)
	public ModelAndView addComment(HttpServletRequest request,
			@ModelAttribute(value="newComment") Comment newComment) throws IOException {
		ModelAndView result = new ModelAndView("redirect:/getAllComments"); 
		
		LOGGER.info("/addComment: " + newComment);
		BlobstoreService blobstoreService = BlobstoreServiceFactory.getBlobstoreService();
		Map<String, List<BlobKey>> blobs = blobstoreService.getUploads(request);
		
		if (blobs != null && blobs.get("image") != null ) {
			BlobKey blobKey = blobs.get("image").get(0);
			//generate direct URL to image
			ImagesService imagesService = ImagesServiceFactory.getImagesService();
			String imageUrl = imagesService.getServingUrl(ServingUrlOptions.Builder.withBlobKey(blobKey));
			LOGGER.info("Setting image for comment. Image blobKey=" + blobKey.getKeyString() + ", imageURL=" + imageUrl);
			newComment.setImageUrl(imageUrl);
			newComment.setImageBlobKey(blobKey.getKeyString());
		}
        
        this.commentDAO.addComment(newComment);
		
		return result;
	}

	/**
	 * Removes old comments
	 * @param retentionInMinutes comments older than this amount of minutes will be removed
	 * @return
	 */
	@RequestMapping(value="/removeOldComments", method=RequestMethod.GET)
	public ModelAndView removeOldComments(
			@RequestParam(value="retentionInMinutes", required=false) Integer retentionInMinutes,
			@RequestParam(value="isCron", required=false, defaultValue="false") Boolean isCron) {
		ModelAndView result = null;
		LOGGER.info("/removeOldComments");
		
		if( retentionInMinutes == null ) {
			retentionInMinutes = commentsRetentionMinutes;
		}
		
		Queue queue = QueueFactory.getDefaultQueue();
		TaskOptions taskOptions = TaskOptions.Builder
				.withUrl("/removeOldCommentsTaskHandler")
				.param("retentionInMinutes", retentionInMinutes.toString())
				.param("isCron", isCron.toString()).method(Method.POST);		
		queue.add(taskOptions);
		
		if( isCron ) {
			//for cron jobs redirects (HTTP 302) are not allowed.
			//Only HTTP 200-299 are recognized as
			//successful execution
			result = new ModelAndView("index");
		} else {
			//when invoked manually,
			//after execution redirect to comments page
			result = new ModelAndView("redirect:/getAllComments");
		}
		return result;
	}
	
	private Comment prepareNewCommentPlaceholder() {
		Comment result = new Comment();
		return result;
	}
	
	/**
	 * Handles task requests
	 * @param request
	 * @param response
	 * @param retentionInMinutes
	 * @param isCron
	 * @return
	 */
	@RequestMapping(value="/removeOldCommentsTaskHandler", method=RequestMethod.POST, produces="text/plain")
	@ResponseBody
	public String removeOldCommentsTaskHandler(HttpServletRequest request,
			HttpServletResponse response,
			@RequestParam(value="retentionInMinutes", required=false) Integer retentionInMinutes,
			@RequestParam(value="isCron", required=false, defaultValue="false") Boolean isCron
			) {
		LOGGER.info("Removing old entries using queue push task");
		commentDAO.removeOldEntries(retentionInMinutes);
		return "Old comments have been removed";
	}
	
}
